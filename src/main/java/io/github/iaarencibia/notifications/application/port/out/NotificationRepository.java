package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;

import java.util.Optional;

/**
 * What the application needs from storage, said in the language of the domain.
 *
 * <p>Only the two operations the intake endpoint performs. The claim query, the reaper and the
 * lookup by id belong to the use cases that call them, and arrive with those.
 */
public interface NotificationRepository {

    /**
     * Stores a notification that does not exist yet.
     *
     * <p>Storing and enqueueing are the same write: the row is the queue.
     *
     * <p>It reports a taken idempotency key by throwing rather than by returning a result,
     * because the exclusion is the database's -- a check performed first in Java would leave a
     * window open between the two statements that a second concurrent request fits through.
     *
     * @param notification the notification to store
     * @throws IdempotencyKeyAlreadyUsedException if a notification already holds that key
     */
    void save(Notification notification);

    /**
     * @param idempotencyKey the key a client sent to make its request safe to repeat; never
     *                       {@code null}, since a caller that sent no key has nothing to look up
     * @return the notification that key already created, or empty if it is unused
     */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);
}
