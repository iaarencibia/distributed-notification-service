package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;

/**
 * Delivers a notification by posting it to the URL the client named as the recipient.
 *
 * <p>This is the channel that gives the retry requirement something to be about. The log channel
 * cannot fail; a remote service can fail in two ways that must not be treated alike, and telling
 * them apart is the whole job here:
 *
 * <ul>
 *   <li><strong>2xx</strong> -- delivered.</li>
 *   <li><strong>5xx, a timeout, a refused connection</strong> -- the destination is unwell, not
 *       the request. Retrying is what fixes it.</li>
 *   <li><strong>4xx</strong> -- the destination understood and refused. Retrying an identical
 *       request would produce an identical refusal, so it is spent.</li>
 *   <li><strong>429</strong> -- a 4xx by number and a transient failure in meaning: the
 *       destination is asking for less traffic, not rejecting the message. It carries the one
 *       hint a destination can give about when to return, and that hint is honoured.</li>
 * </ul>
 *
 * <p>Nothing here retries or sleeps. One call, one outcome; how many attempts a notification gets
 * and how far apart belongs to the dispatcher and the retry policy, and a channel that also
 * retried would silently multiply both.
 */
class HttpServiceChannelSender implements NotificationChannelSender {

    /** The identifier the destination can use to discard a delivery it has already accepted. */
    static final String NOTIFICATION_ID_HEADER = "X-Notification-Id";

    /** Groups this delivery with the rest of its operation, on the destination's side too. */
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final RestClient restClient;

    HttpServiceChannelSender(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Channel channel() {
        return Channel.SERVICE;
    }

    @Override
    public DispatchOutcome send(Notification notification) {
        URI destination;
        try {
            destination = new URI(notification.payload().recipient());
        } catch (URISyntaxException malformed) {
            return new DispatchOutcome.PermanentFailure(null,
                    "recipient is not a valid URI: " + malformed.getReason());
        }

        // Parsing is not enough: "example.com/hook" is a legal relative URI, and so are plenty of
        // shapes no HTTP call can be built from. Each is permanent -- the recipient is stored on
        // the row and no retry rewrites it. Whether the host answers is a different question, and
        // the attempt below is what classifies that.
        String unusable = whyUnusable(destination);
        if (unusable != null) {
            return new DispatchOutcome.PermanentFailure(null, unusable);
        }

        try {
            return restClient.post()
                    .uri(destination)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(NOTIFICATION_ID_HEADER, notification.id().toString())
                    .header(CORRELATION_ID_HEADER, notification.correlationId().value())
                    .body(bodyOf(notification))
                    .exchange((request, response) -> classify(response.getStatusCode(),
                            response.getHeaders().getFirst("Retry-After")));
        } catch (ResourceAccessException unreachable) {
            // Everything below the response: connect timeout, read timeout, refused connection,
            // a name that does not resolve. No answer arrived, so there is no status to record,
            // and every one of them is worth trying again.
            return new DispatchOutcome.RetryableFailure(null, describe(unreachable), null);
        } catch (RuntimeException refused) {
            // The backstop, and the reason it exists rather than a longer list of checks above:
            // this port promises never to throw, and the checks can only cover the shapes already
            // thought of. Twice now a recipient got past them and blew up here. Permanent, because
            // a request that cannot be built from an unchanging recipient will never be built.
            return new DispatchOutcome.PermanentFailure(null,
                    "recipient cannot be reached as an HTTP destination -- " + describe(refused));
        }
    }

    /**
     * @param destination the parsed recipient
     * @return why no HTTP request can be built from it, or {@code null} when one can
     */
    private static String whyUnusable(URI destination) {
        if (!destination.isAbsolute()) {
            return "recipient must be an absolute URL, and this one names no scheme";
        }
        if (!isHttp(destination.getScheme())) {
            return "recipient must use http or https, and this one uses " + destination.getScheme();
        }
        if (destination.getHost() == null || destination.getHost().isBlank()) {
            return "recipient names no host";
        }
        // -1 is "no port given", which is legal and means the scheme's default.
        if (destination.getPort() != -1 && (destination.getPort() < 1 || destination.getPort() > 65535)) {
            return "recipient names a port outside the legal range: " + destination.getPort();
        }
        return null;
    }

    /**
     * The wire format of the outbound call. Only the payload the client submitted travels: the
     * lifecycle of the notification is this service's business, and a destination that received
     * an attempt count would be reading state it has no say over.
     */
    private static Map<String, Object> bodyOf(Notification notification) {
        return Map.of(
                "recipient", notification.payload().recipient(),
                "subject", notification.payload().subject(),
                "body", notification.payload().body(),
                "priority", notification.payload().priority().name(),
                "metadata", notification.payload().metadata());
    }

    private static DispatchOutcome classify(HttpStatusCode status, String retryAfter) {
        if (status.is2xxSuccessful()) {
            return new DispatchOutcome.Success(status.value());
        }
        if (status.value() == 429) {
            return new DispatchOutcome.RetryableFailure(status.value(),
                    "the destination asked for fewer requests", retryAfterOf(retryAfter));
        }
        if (status.is4xxClientError()) {
            return new DispatchOutcome.PermanentFailure(status.value(),
                    "the destination rejected the notification with " + status.value());
        }
        // 5xx, and any status that is neither success nor a client error. A destination
        // answering something this service does not recognise is treated as unwell rather than
        // as having refused: the expensive mistake is discarding a notification that would have
        // been delivered on the next attempt.
        return new DispatchOutcome.RetryableFailure(status.value(),
                "the destination answered " + status.value(), null);
    }

    /**
     * Reads {@code Retry-After} in its delay-seconds form only. The HTTP-date form is legal and
     * deliberately ignored: honouring it means trusting the destination's clock against ours, and
     * a skew there turns into a notification that sleeps for hours. Absent or unreadable gives
     * null, which means "no instruction" -- and the retry policy falls back to its own backoff,
     * which is never worse than what it would have done anyway.
     *
     * <p>Zero passes through, and it reads like an oversight, so: it is deliberate. Nothing is
     * retried immediately because the dispatcher polls, and what bounds a destination's load is
     * the attempt budget rather than the delay. This channel reports the instruction and does
     * not judge it; capping and flooring belong to the retry policy.
     */
    private static Duration retryAfterOf(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notADelay) {
            return null;
        }
    }

    /**
     * @param scheme the scheme of the destination, never null here because the URI is absolute
     * @return whether this channel speaks it
     */
    private static boolean isHttp(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * The class name is kept alongside the message because the message of a timeout is often
     * the URL and nothing else, and a recorded failure that does not say what went wrong cannot
     * answer the question the attempt history exists for.
     */
    private static String describe(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
