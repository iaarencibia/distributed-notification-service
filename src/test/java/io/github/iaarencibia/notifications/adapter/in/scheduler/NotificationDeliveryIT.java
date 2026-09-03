package io.github.iaarencibia.notifications.adapter.in.scheduler;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A notification from the moment it is accepted to the moment it is delivered, or given up on.
 *
 * <p>This is the requirement the whole design exists for, and the only test that exercises all of
 * it at once: the intake writes a row, the claim query finds it, the SERVICE channel posts to a
 * destination that is really there, the outcome is classified, and the notification is either
 * rescheduled or finished.
 *
 * <p>It drives the use case rather than waiting for the timer. What a scheduler adds is that the
 * pass runs again in a second; asserting on that would mean sleeping for one, and would make every
 * failure here ambiguous between "the dispatch is wrong" and "the tick had not fired yet".
 *
 * <p>The backoff is compressed to a millisecond so that a retry is due by the time the next pass
 * runs. The schedule itself is asserted where it is decided, in the retry policy's own tests.
 */
@SpringBootTest(properties = {
        "notifications.retry.initial-backoff=1ms",
        "notifications.retry.max-backoff=1ms",
        "notifications.retry.jitter=0.0"})
class NotificationDeliveryIT extends IntegrationTestSupport {

    private static final WireMockServer DESTINATION = new WireMockServer(options().dynamicPort());

    @Autowired
    private SubmitNotificationUseCase intake;

    @Autowired
    private DispatchDueNotificationsUseCase dispatcher;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startTheDestination() {
        DESTINATION.start();
    }

    @AfterAll
    static void stopTheDestination() {
        DESTINATION.stop();
    }

    @BeforeEach
    void emptyTheTableAndTheStubs() {
        jdbc.execute("TRUNCATE TABLE notification CASCADE");
        DESTINATION.resetAll();
    }

    @Test
    @DisplayName("delivers a notification the destination accepts, and stops there")
    void deliversOnTheFirstAttempt() {
        DESTINATION.stubFor(post(urlEqualTo("/hook/ok")).willReturn(aResponse().withStatus(202)));
        UUID id = submitTo("/hook/ok");

        dispatchUntilSettled();

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(outcomesOf(id)).containsExactly("SUCCESS");
        DESTINATION.verify(1, postRequestedFor(urlEqualTo("/hook/ok")));
    }

    @Test
    @DisplayName("retries a destination that is unwell, and delivers when it recovers")
    void retriesUntilTheDestinationRecovers() {
        // The requirement in one test: a 503 is a condition that may pass, so the notification is
        // rescheduled rather than failed, and its history keeps both failures and the success.
        DESTINATION.stubFor(post(urlEqualTo("/hook/flaky"))
                .inScenario("recovering").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed once"));
        DESTINATION.stubFor(post(urlEqualTo("/hook/flaky"))
                .inScenario("recovering").whenScenarioStateIs("failed once")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        DESTINATION.stubFor(post(urlEqualTo("/hook/flaky"))
                .inScenario("recovering").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        UUID id = submitTo("/hook/flaky");

        dispatchUntilSettled();

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(attemptsOf(id)).isEqualTo(3);
        assertThat(outcomesOf(id))
                .as("the history keeps every attempt, not only the one that worked")
                .containsExactly("RETRYABLE_FAILURE", "RETRYABLE_FAILURE", "SUCCESS");
        DESTINATION.verify(3, postRequestedFor(urlEqualTo("/hook/flaky")));
    }

    @Test
    @DisplayName("gives up on a destination that refuses, without spending the budget")
    void failsAtOnceOnARefusal() {
        // A 400 says the request itself is wrong, and sending it again would not make it right.
        // Retrying here would be three calls that cannot succeed and a notification that takes
        // minutes to reach the state it was already in on the first attempt.
        DESTINATION.stubFor(post(urlEqualTo("/hook/broken")).willReturn(aResponse().withStatus(400)));
        UUID id = submitTo("/hook/broken");

        dispatchUntilSettled();

        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(attemptsOf(id))
                .as("one attempt out of a budget of three: the classification ended this, not the budget")
                .isEqualTo(1);
        assertThat(outcomesOf(id)).containsExactly("PERMANENT_FAILURE");
        DESTINATION.verify(1, postRequestedFor(urlEqualTo("/hook/broken")));
    }

    @Test
    @DisplayName("gives up on a destination that never recovers, once the budget is spent")
    void failsWhenTheBudgetRunsOut() {
        DESTINATION.stubFor(post(urlEqualTo("/hook/down")).willReturn(aResponse().withStatus(503)));
        UUID id = submitTo("/hook/down");

        dispatchUntilSettled();

        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(attemptsOf(id)).isEqualTo(3);
        assertThat(outcomesOf(id))
                .containsExactly("RETRYABLE_FAILURE", "RETRYABLE_FAILURE", "RETRYABLE_FAILURE");
        DESTINATION.verify(3, postRequestedFor(urlEqualTo("/hook/down")));
    }

    /**
     * Runs passes until nothing is claimable any more.
     *
     * <p>A pass returns as soon as the batch is handed to the workers, so a bounded wait between
     * passes is unavoidable: what is being waited for is a delivery over a socket. The loop ends
     * on the state of the table rather than on a fixed number of passes, so a schedule that stops
     * making progress fails here rather than passing by coincidence.
     */
    private void dispatchUntilSettled() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            dispatcher.dispatchDue();
            if (settled()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("no notification reached a terminal state within thirty seconds");
    }

    /** @return whether every notification has finished, one way or the other */
    private boolean settled() {
        Integer unfinished = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE status IN ('PENDING', 'DISPATCHING')",
                Integer.class);
        return unfinished != null && unfinished == 0;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a delivery", interrupted);
        }
    }

    /**
     * @param path where on the destination the notification should be posted
     * @return the identity of the notification the intake accepted
     */
    private UUID submitTo(String path) {
        NotificationPayload payload = new NotificationPayload(DESTINATION.baseUrl() + path,
                Channel.SERVICE, "a subject", "a body", Priority.MEDIUM, Map.of());

        return intake.submit(new SubmitNotificationCommand(payload,
                new CorrelationId("order-4471"), "key-" + UUID.randomUUID()));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private Integer attemptsOf(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM notification WHERE id = ?", Integer.class,
                id);
    }

    /** @return every attempt's outcome, in the order they were made */
    private List<String> outcomesOf(UUID id) {
        return jdbc.queryForList("SELECT outcome FROM notification_attempt "
                + "WHERE notification_id = ? ORDER BY attempt_number", String.class, id);
    }
}
