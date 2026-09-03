package io.github.iaarencibia.notifications.adapter.out.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SERVICE channel against a real HTTP server.
 *
 * <p>WireMock runs in-process on an ephemeral port, so this is a unit test in every way that
 * matters: no container, no stack, no fixed port to collide with. What it needs a real server for
 * is that the distinction under test -- a destination that refuses versus one that is unwell --
 * is only visible over a socket. A stubbed {@code RestClient} would return whatever it was told
 * to, which is the answer rather than the question.
 *
 * <p>The read timeout is deliberately short. A test that waits five seconds to prove a timeout is
 * a test people start skipping.
 */
class HttpServiceChannelSenderTest {

    /**
     * Short enough that proving a timeout costs about a second, and wide enough that it never
     * fires on a destination that did answer. Three hundred milliseconds was not: the first
     * request against a freshly started WireMock takes several seconds to come back, so whichever
     * case happened to run first failed with a timeout that said nothing about the code under
     * test. The warm-up below removes that cost from the measurement rather than hiding it behind
     * a number nobody could explain.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    private static final String PATH = "/hook";

    private static WireMockServer destination;
    private HttpServiceChannelSender sender;

    @BeforeAll
    static void startDestination() throws Exception {
        destination = new WireMockServer(options().dynamicPort());
        destination.start();

        // One throwaway round trip, so the cost of a cold server is paid here instead of by
        // whichever case JUnit happens to run first.
        destination.stubFor(post(urlEqualTo("/warm-up")).willReturn(aResponse().withStatus(200)));
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(destination.baseUrl() + "/warm-up"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    static void stopDestination() {
        destination.stop();
    }

    @BeforeEach
    void resetAndBuildSender() {
        destination.resetAll();

        // The production factory, not one built for the test: the classification under test
        // depends on how the client reports a timeout and a bodiless response, so a different
        // client here would be exercising something this service never runs.
        sender = new HttpServiceChannelSender(RestClient.builder()
                .requestFactory(ChannelConfig.requestFactoryFor(
                        new NotificationsProperties.Channels.Service(
                                Duration.ofMillis(500), READ_TIMEOUT)))
                .build());
    }

    @Test
    @DisplayName("answers for the SERVICE channel and no other")
    void answersForTheServiceChannel() {
        assertThat(sender.channel()).isEqualTo(Channel.SERVICE);
    }

    @Test
    @DisplayName("puts each configured timeout where it belongs, and not the other way round")
    void doesNotSwapTheTwoTimeouts() {
        // Two Durations of the same type, assigned one after the other: swapping them compiles,
        // changes behaviour, and every other test here stays green. What tells them apart is a
        // destination that answers slowly -- only the read timeout is measured against that.
        HttpServiceChannelSender patient = new HttpServiceChannelSender(RestClient.builder()
                .requestFactory(ChannelConfig.requestFactoryFor(
                        new NotificationsProperties.Channels.Service(
                                Duration.ofMillis(500), Duration.ofSeconds(3))))
                .build());
        destination.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        // A second and a half is far past the connect timeout and well inside the read timeout.
        // Swapped, the client would give up at five hundred milliseconds and report a failure.
        assertThat(patient.send(notification()))
                .isEqualTo(new DispatchOutcome.Success(200));
    }

    @Test
    @DisplayName("reports a delivery when the destination accepts it")
    void reportsSuccessOnAcceptance() {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isEqualTo(new DispatchOutcome.Success(202));
    }

    @Test
    @DisplayName("sends the identifiers the destination needs to discard a repeat")
    void sendsTheIdentifyingHeaders() {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        Notification notification = notification();

        sender.send(notification);

        // The point of the outbound identifier: a destination that receives the same delivery
        // twice -- because this service retried one it had in fact accepted -- can tell.
        destination.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader(HttpServiceChannelSender.NOTIFICATION_ID_HEADER,
                        equalTo(notification.id().toString()))
                .withHeader(HttpServiceChannelSender.CORRELATION_ID_HEADER,
                        equalTo("order-4471")));
    }

    @Test
    @DisplayName("treats a server error as worth trying again")
    void treatsServerErrorAsRetryable() {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isInstanceOf(DispatchOutcome.RetryableFailure.class);
        assertThat(outcome.responseCode()).isEqualTo(503);
        assertThat(outcome.errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("treats a rejection as permanent, because the same request would be rejected again")
    void treatsClientErrorAsPermanent() {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isInstanceOf(DispatchOutcome.PermanentFailure.class);
        assertThat(outcome.responseCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("treats being rate limited as transient, and honours the destination's own delay")
    void honoursRetryAfterOnRateLimiting() {
        // 429 is a 4xx by number and a transient failure in meaning. Classified with the rest of
        // the 4xx family it would burn a notification for the one reason that resolves by itself.
        destination.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isInstanceOf(DispatchOutcome.RetryableFailure.class);
        assertThat(((DispatchOutcome.RetryableFailure) outcome).retryAfter())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("asks for no particular delay when the destination gives none it can read")
    void reportsNoDelayWhenRetryAfterIsUnreadable() {
        // The HTTP-date form is legal and deliberately unsupported: honouring it means trusting
        // the destination's clock against ours. Null is not zero -- zero would mean "retry now".
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(((DispatchOutcome.RetryableFailure) outcome).retryAfter()).isNull();
    }

    @Test
    @DisplayName("treats a destination that goes quiet as worth trying again, with no status")
    void treatsATimeoutAsRetryable() {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(3).toMillis())));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isInstanceOf(DispatchOutcome.RetryableFailure.class);
        assertThat(outcome.responseCode())
                .as("no answer arrived, so there is no status to record")
                .isNull();
        assertThat(outcome.errorMessage())
                .as("a failure with no reason cannot explain itself later")
                .isNotBlank();
    }

    @Test
    @DisplayName("rejects a recipient that is not a URL at all, without trying to reach it")
    void rejectsAMalformedRecipient() {
        DispatchOutcome outcome = sender.send(notificationTo("not a url at all"));

        assertThat(outcome).isInstanceOf(DispatchOutcome.PermanentFailure.class);
        assertThat(destination.getAllServeEvents())
                .as("a value that cannot be a URL is not worth a round trip")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com/hook",        // legal relative URI: parses, is not absolute
            "someone@example.test",    // what a caller sends after picking the wrong channel
            "/hook",                   // an absolute path is still a relative reference
            "file:///etc/passwd",      // absolute, and a scheme this channel does not speak
            "mailto:someone@example.test",
            "foo://host/path",
            "http://example.com:99999/hook",   // absolute http, port past the legal range
            "http://example.com:-5/hook",      // absolute http, port that is not a port
            "http:///hook"})                   // absolute http, no host to reach
    @DisplayName("answers, never throws, for a recipient no HTTP call can be built from")
    void neverThrowsForAnUnusableRecipient(String recipient) {
        // The port promises that a failed delivery is an outcome and not an exception, and this
        // is where that promise was broken: these parse as URIs and then throw unchecked past the
        // catch below. A dispatcher trusting the promise would lose the attempt entirely, with no
        // outcome recorded and no retry decision made.
        DispatchOutcome outcome = sender.send(notificationTo(recipient));

        assertThat(outcome)
                .as("every unusable recipient is permanent: the row keeps it, no retry rewrites it")
                .isInstanceOf(DispatchOutcome.PermanentFailure.class);
        assertThat(outcome.errorMessage()).isNotBlank();
        assertThat(destination.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 422, 451})
    @DisplayName("treats every client rejection as permanent, not only the one that was easy")
    void treatsEveryClientErrorAsPermanent(int status) {
        // Pinned across the family rather than at 400 alone. Narrowed to a single status, a 404
        // or a 422 would be classified as transient and retried until the budget was spent.
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isInstanceOf(DispatchOutcome.PermanentFailure.class);
        assertThat(outcome.responseCode()).isEqualTo(status);
    }

    @Test
    @DisplayName("sends the payload the destination needs, and nothing about the lifecycle")
    void sendsThePayloadAndNoLifecycle() throws Exception {
        destination.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        sender.send(notification());

        // The published outbound contract, asserted field by field. Without this the body could
        // be an empty object and every other test here would still pass.
        destination.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", containing(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(matchingJsonPath("$.recipient"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your order shipped")))
                .withRequestBody(matchingJsonPath("$.body", equalTo("It is on its way.")))
                .withRequestBody(matchingJsonPath("$.priority", equalTo("HIGH")))
                .withRequestBody(matchingJsonPath("$.metadata.orderId", equalTo("4471"))));

        // What the destination has no say over must not travel: an attempt count or a status
        // would be this service's state, read by something that cannot act on it.
        JsonNode sent = new ObjectMapper().readTree(
                destination.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("recipient", "subject", "body", "priority", "metadata");
    }

    private Notification notification() {
        return notificationTo(destination.baseUrl() + PATH);
    }

    private static Notification notificationTo(String recipient) {
        return Notification.submit(UUID.randomUUID(),
                new NotificationPayload(recipient, Channel.SERVICE, "Your order shipped",
                        "It is on its way.", Priority.HIGH, Map.of("orderId", "4471")),
                new CorrelationId("order-4471"), "key-1", 3, Instant.now());
    }
}
