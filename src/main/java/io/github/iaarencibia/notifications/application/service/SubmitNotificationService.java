package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.Notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts a notification and, by the same write, queues it.
 *
 * <p>Deliberately not transactional. There is one write here and nothing to hold together, and a
 * transaction spanning the whole method would break the very recovery below: the failed insert
 * dooms the transaction it ran in, so the lookup that follows would run inside something already
 * marked for rollback and the commit would fail regardless of what was read. Letting each call on
 * the port carry its own short transaction is what makes "insert, collide, return the existing
 * one" possible at all.
 *
 * <p>Nothing here reaches the dispatcher. The row is the queue, so a service whose dispatch side
 * is entirely down still accepts work -- which is the requirement, met by construction rather
 * than by a fallback.
 */
public class SubmitNotificationService implements SubmitNotificationUseCase {

    private final NotificationRepository repository;
    private final int maxAttempts;

    /**
     * @param repository  where notifications are stored, and by that fact queued
     * @param maxAttempts the delivery budget stamped on every notification as it is created
     */
    public SubmitNotificationService(NotificationRepository repository, int maxAttempts) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.maxAttempts = maxAttempts;
    }

    @Override
    public UUID submit(SubmitNotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Read from the JVM. The rule is that every instant the domain sees comes from
        // PostgreSQL, and it is applied in the PR that brings the dispatcher, where a skew
        // between instances is what makes the reaper rescue rows that are still being sent. In
        // the delivered stack the application and the database share a host, so the difference
        // here is zero; across machines it would delay a first attempt by the skew and nothing
        // more, because being late to become claimable is harmless.
        Instant now = Instant.now();

        Notification notification = Notification.submit(UUID.randomUUID(), command.payload(),
                command.correlationId(), command.idempotencyKey(), maxAttempts, now);

        try {
            repository.save(notification);
            return notification.id();
        } catch (IdempotencyKeyAlreadyUsedException alreadyUsed) {
            // Not a failure: it is the guarantee doing its job. The caller repeated a request --
            // a network retry, most of the time -- and gets back the identifier that its first
            // one created, so the repeat delivers nothing twice.
            return repository.findByIdempotencyKey(alreadyUsed.idempotencyKey())
                    .map(Notification::id)
                    // The row that rejected the insert is gone by the time it is read back. No
                    // path in this service deletes one, so this is not a repeated request that
                    // lost a race; it is corruption, and it is not dressed up as success.
                    .orElseThrow(() -> alreadyUsed);
        }
    }
}
