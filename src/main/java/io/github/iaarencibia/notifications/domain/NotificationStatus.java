package io.github.iaarencibia.notifications.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lifecycle of a notification, and the only place in the code where the legal moves
 * between its states are declared.
 *
 * <p>{@code DISPATCHING} is a persisted logical lock. It replaces the row lock so that no
 * transaction stays open across a network call; the reaper is what releases it if its owner
 * dies.
 *
 * <p>The same graph is enforced again by the {@code ck_notification_status} check
 * constraint: this one stops the code from attempting an illegal move, the other stops an
 * illegal state from being stored.
 */
public enum NotificationStatus {

    /** Persisted and waiting to be claimed. The state a notification is created in. */
    PENDING,

    /** Claimed by an instance, which owns it until it records a result or the reaper reclaims it. */
    DISPATCHING,

    /** Delivered. Terminal. */
    SENT,

    /** Permanently rejected, or out of attempts. Terminal. */
    FAILED;

    /**
     * {@code DISPATCHING -> PENDING} covers two moves that are identical from here: a
     * scheduled retry, and a claim released by the reaper. What tells them apart is the
     * attempt history -- a retry leaves a row in {@code notification_attempt}, a reaped claim
     * does not.
     */
    private static final Map<NotificationStatus, Set<NotificationStatus>> TRANSITIONS = Map.of(
            PENDING, Set.of(DISPATCHING),
            DISPATCHING, Set.of(SENT, FAILED, PENDING),
            SENT, Set.of(),
            FAILED, Set.of());

    /**
     * Whether moving from this state to another is a legal step of the lifecycle.
     *
     * @param target the state being moved to
     * @return {@code true} when the move is declared in the transition graph
     */
    public boolean canTransitionTo(NotificationStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return TRANSITIONS.get(this).contains(target);
    }

    /**
     * Whether this state has no outgoing transition, and the notification is therefore done.
     *
     * <p>Derived from the graph rather than declared separately, so a state cannot be
     * terminal and still have a way out.
     *
     * @return {@code true} when no transition leaves this state
     */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
