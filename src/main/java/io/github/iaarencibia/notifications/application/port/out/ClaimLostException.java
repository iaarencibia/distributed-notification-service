package io.github.iaarencibia.notifications.application.port.out;

import java.util.UUID;

/**
 * Thrown when the result of a delivery arrives for a notification that has moved on since it was
 * claimed: the reaper judged the claim abandoned and released it.
 *
 * <p>What happened after that is not known here. The row may already have been claimed and
 * delivered by another instance, or it may simply be waiting to be. Either way this write is
 * refused, because the state it was computed from is no longer the state of the row.
 *
 * <p>It is an expected event, not a defect, and that is the whole reason it has a name: a write
 * refused by a unique constraint arrives as an integrity violation, indistinguishable from a bug
 * worth waking somebody for. This one says the instance lost a race, which is something the
 * dispatcher knows how to do nothing about.
 */
public class ClaimLostException extends RuntimeException {

    private final UUID notificationId;

    /**
     * @param notificationId the notification whose claim was lost
     */
    public ClaimLostException(UUID notificationId) {
        super("the claim on notification " + notificationId + " was lost before its result "
                + "could be recorded");
        this.notificationId = notificationId;
    }

    /**
     * @return the notification whose claim was lost
     */
    public UUID notificationId() {
        return notificationId;
    }
}
