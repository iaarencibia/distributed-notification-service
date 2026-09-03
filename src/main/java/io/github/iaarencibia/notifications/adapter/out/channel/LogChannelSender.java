package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers a notification by writing it as a structured log line.
 *
 * <p>The channel the brief requires to be always present, and the only one that cannot fail for
 * a reason outside this process: there is no network, no destination and no timeout. It reports
 * {@link DispatchOutcome.Success} with no status code, because there is no status to report --
 * which is exactly why {@code responseCode} is allowed to be null.
 *
 * <p>The fields are attached as key-value pairs rather than concatenated into the message, so
 * that they arrive in the JSON as members a log collector can filter on. A line that spells the
 * payload into its own text is searchable by substring and by nothing else.
 *
 * <p>The body is deliberately left out. It is the widest field a notification carries, and a log
 * line is not where its content belongs; what this line has to answer is that the delivery
 * happened, to whom, and as part of which operation.
 */
@Component
class LogChannelSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(LogChannelSender.class);

    @Override
    public Channel channel() {
        return Channel.LOG;
    }

    @Override
    public DispatchOutcome send(Notification notification) {
        // SLF4J's fluent API rather than a logging-framework helper: the key-value pairs are
        // read by the ECS formatter Spring Boot already configures, so this adds no dependency
        // and no encoder of its own.
        log.atInfo()
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("correlationId", notification.correlationId().value())
                .addKeyValue("recipient", notification.payload().recipient())
                .addKeyValue("subject", notification.payload().subject())
                .addKeyValue("priority", notification.payload().priority())
                .addKeyValue("attempt", notification.attempts() + 1)
                .setMessage("Notification delivered to the log channel")
                .log();

        return new DispatchOutcome.Success(null);
    }
}
