package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.NotificationAttempt;

import java.util.List;

/**
 * The queue, from the dispatcher's side: take work, then report what came of it.
 *
 * <p>Two calls and not one because the network call goes between them. A single operation holding
 * a notification from claim to result would keep a database connection for as long as a
 * destination takes to answer, and a few slow ones would empty the pool the intake writes through.
 */
public interface NotificationDispatchQueue {

    /**
     * Takes ownership of up to {@code batchSize} notifications that are due, highest priority
     * first and, within a priority, whichever has been due longest. Rows another instance is
     * already claiming are skipped rather than waited for, so two instances polling at once take
     * different work instead of queueing behind each other.
     *
     * @param batchSize how many to take at most
     * @param claimedBy the instance taking them, recorded so the reaper can tell whose work was
     *                  abandoned
     * @return the notifications now owned by this instance, possibly none
     */
    List<ClaimedNotification> claimDue(int batchSize, String claimedBy);

    /**
     * Stores the outcome of one delivery: the attempt, and the state it left the notification in.
     * Both in one transaction, because an attempt with no transition would be a delivery the
     * system cannot account for, and a transition with no attempt would lose the reason.
     *
     * @param claimed the claim being reported on, carrying the version to write against
     * @param attempt the record of what happened
     * @throws ClaimLostException if the notification moved on since it was claimed
     */
    void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt);
}
