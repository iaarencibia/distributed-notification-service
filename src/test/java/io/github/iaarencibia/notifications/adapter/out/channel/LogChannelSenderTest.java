package io.github.iaarencibia.notifications.adapter.out.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LOG channel, which the brief requires to be present in every deployment.
 *
 * <p>The log line is the delivery, so asserting that the method returned is not enough: a version
 * of this channel that reported success and wrote nothing would satisfy every caller and deliver
 * nothing at all. What is asserted is therefore the event itself, captured with an appender
 * attached to the real logger, and the key-value pairs the ECS formatter turns into members a log
 * collector can filter on.
 */
class LogChannelSenderTest {

    private final LogChannelSender sender = new LogChannelSender();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureTheLogger() {
        logger = (Logger) LoggerFactory.getLogger(LogChannelSender.class);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void releaseTheLogger() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("answers for the LOG channel and no other")
    void answersForTheLogChannel() {
        assertThat(sender.channel()).isEqualTo(Channel.LOG);
    }

    @Test
    @DisplayName("reports a delivery, with no status code because there is none to report")
    void reportsSuccessWithNoStatusCode() {
        // The one channel that cannot fail for a reason outside this process: no network, no
        // destination, no timeout. Null is not a missing value here, it is the accurate one.
        DispatchOutcome outcome = sender.send(notification());

        assertThat(outcome).isEqualTo(new DispatchOutcome.Success(null));
    }

    @Test
    @DisplayName("writes exactly one line, because the line is the delivery")
    void writesTheLine() {
        sender.send(notification());

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("attaches the fields a collector filters on, rather than spelling them into text")
    void attachesTheFieldsAsKeyValuePairs() {
        Notification notification = notification();

        sender.send(notification);

        Map<String, Object> fields = fieldsOf(captured.list.getFirst());
        assertThat(fields).containsEntry("notificationId", notification.id());
        assertThat(fields).containsEntry("correlationId", "order-4471");
        assertThat(fields).containsEntry("recipient", "someone@example.test");
        assertThat(fields).containsEntry("subject", "Your order shipped");
        assertThat(fields).containsEntry("priority", Priority.HIGH);
    }

    @Test
    @DisplayName("numbers the attempt from one, since the row counts attempts already recorded")
    void numbersTheAttemptFromOne() {
        // A notification that has never been tried carries attempts = 0, and the delivery this
        // line reports is its first. Logging the stored counter would put every attempt in the
        // trace one behind the attempt it describes.
        assertThat(fieldsOf(sendAndCapture(notification()))).containsEntry("attempt", 1);
    }

    @Test
    @DisplayName("does not write the body, which is the widest thing a notification carries")
    void doesNotWriteTheBody() {
        sender.send(notification());

        ILoggingEvent event = captured.list.getFirst();
        assertThat(fieldsOf(event)).doesNotContainKey("body");
        assertThat(event.getFormattedMessage()).doesNotContain("It is on its way.");
    }

    private ILoggingEvent sendAndCapture(Notification notification) {
        sender.send(notification);
        return captured.list.getFirst();
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static Notification notification() {
        return Notification.submit(UUID.randomUUID(),
                new NotificationPayload("someone@example.test", Channel.LOG, "Your order shipped",
                        "It is on its way.", Priority.HIGH, Map.of("orderId", "4471")),
                new CorrelationId("order-4471"), "key-1", 3, Instant.now());
    }
}
