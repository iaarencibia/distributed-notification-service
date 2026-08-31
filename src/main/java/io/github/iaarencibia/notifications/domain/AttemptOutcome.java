package io.github.iaarencibia.notifications.domain;

/**
 * The label stored in {@code notification_attempt.outcome}. Its constants are exactly the
 * values the {@code ck_attempt_outcome} check constraint accepts.
 *
 * <p>Separate from {@link DispatchOutcome}, which carries the detail of one attempt -- the
 * status code, the message, the {@code Retry-After} hint. This is only the name that
 * survives in the history.
 */
public enum AttemptOutcome {

    SUCCESS,

    RETRYABLE_FAILURE,

    PERMANENT_FAILURE
}
