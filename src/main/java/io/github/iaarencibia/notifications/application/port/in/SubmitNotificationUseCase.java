package io.github.iaarencibia.notifications.application.port.in;

import java.util.UUID;

/**
 * Accepting a notification for delivery: the way work enters the system, and the only one a
 * caller drives. What becomes of it afterwards belongs to the dispatcher, which no request
 * reaches.
 */
public interface SubmitNotificationUseCase {

    /**
     * Records the notification and, by the same write, queues it.
     *
     * <p>Returns rather than throws when the idempotency key has already been used: a repeated
     * request is the mechanism working, not a failure, and the caller gets the identifier of the
     * notification its first attempt created.
     *
     * @param command what to send, and the identifiers the caller attached to it
     * @return the identifier of the notification, whether this call created it or an earlier one
     *         with the same idempotency key did
     */
    UUID submit(SubmitNotificationCommand command);
}
