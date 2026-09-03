package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
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

    /**
     * Writes an aggregate's lifecycle back onto the row it came from. Only the fields a dispatch
     * moves: identity and payload are fixed at intake, and rewriting them from here would be a
     * second chance to get them wrong.
     *
     * @param entity       the row, as it was read
     * @param notification the aggregate, as the dispatch left it
     */
    static void applyLifecycle(NotificationEntity entity, Notification notification) {
        entity.applyLifecycle(
                notification.status(),
                notification.attempts(),
                notification.nextAttemptAt(),
                notification.lastError(),
                notification.claimedAt(),
                notification.claimedBy(),
                notification.updatedAt(),
                notification.sentAt());
    }

    /**
     * @param attempt the attempt the aggregate produced
     * @return the row to insert for it, its duration derived once by the domain
     */
    static NotificationAttemptEntity toEntity(NotificationAttempt attempt) {
        return new NotificationAttemptEntity(
                attempt.id(),
                attempt.notificationId(),
                attempt.attemptNumber(),
                attempt.channel(),
                attempt.outcome(),
                attempt.responseCode(),
                attempt.errorMessage(),
                attempt.startedAt(),
                attempt.finishedAt(),
                attempt.durationMs());
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
