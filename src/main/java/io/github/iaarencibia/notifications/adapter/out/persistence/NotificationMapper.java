package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;

/**
 * Translates between the aggregate and the row.
 *
 * <p>It is the whole reason the domain never imports JPA, and it is deliberately dull: both
 * directions are transcriptions, written in the column order of the table so that a crossed pair
 * of same-typed fields is visible by reading the two lists side by side.
 */
final class NotificationMapper {

    private NotificationMapper() {
    }

    static NotificationEntity toEntity(Notification notification) {
        NotificationPayload payload = notification.payload();
        return new NotificationEntity(
                notification.id(),
                payload.recipient(),
                payload.channel(),
                payload.subject(),
                payload.body(),
                payload.priority(),
                payload.metadata(),
                notification.status(),
                notification.attempts(),
                notification.maxAttempts(),
                notification.nextAttemptAt(),
                notification.lastError(),
                notification.claimedAt(),
                notification.claimedBy(),
                notification.correlationId().value(),
                notification.idempotencyKey(),
                notification.createdAt(),
                notification.updatedAt(),
                notification.sentAt());
    }

    static Notification toDomain(NotificationEntity entity) {
        NotificationPayload payload = new NotificationPayload(
                entity.getRecipient(),
                entity.getChannel(),
                entity.getSubject(),
                entity.getBody(),
                entity.getPriority(),
                entity.getMetadata());

        return Notification.rehydrate(
                entity.getId(),
                payload,
                entity.getStatus(),
                entity.getAttempts(),
                entity.getMaxAttempts(),
                entity.getNextAttemptAt(),
                entity.getLastError(),
                entity.getClaimedAt(),
                entity.getClaimedBy(),
                new CorrelationId(entity.getCorrelationId()),
                entity.getIdempotencyKey(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getSentAt());
    }
}
