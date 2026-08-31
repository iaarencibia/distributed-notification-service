package io.github.iaarencibia.notifications.domain;

/**
 * Thrown when a notification is asked to make a move its lifecycle does not allow.
 *
 * <p>Unchecked and loud on purpose: an illegal transition is a defect in the caller, not a
 * condition to recover from. Failing here leaves the notification in the state it was in,
 * rather than persisting an incoherent one.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(NotificationStatus from, NotificationStatus to) {
        super("a notification cannot move from " + from + " to " + to);
    }

    public InvalidStateTransitionException(NotificationStatus from, String attemptedAction) {
        super("a notification in " + from + " cannot " + attemptedAction);
    }
}
