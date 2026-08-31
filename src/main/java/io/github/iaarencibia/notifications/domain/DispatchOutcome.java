package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * The result a channel reports after attempting one delivery.
 *
 * <p>Sealed rather than a boolean paired with a status code, so that a switch over the three
 * cases is checked by the compiler: a fourth outcome would not compile until every caller
 * decided what to do with it.
 */
public sealed interface DispatchOutcome {

    /**
     * The label this outcome is recorded under.
     *
     * @return the value stored in {@code notification_attempt.outcome}
     */
    AttemptOutcome attemptOutcome();

    /**
     * The status code the destination replied with.
     *
     * @return the status code, or {@code null} when there is none: channels that report no
     *         status, and failures where no response ever arrived
     */
    Integer responseCode();

    /**
     * Why the attempt failed.
     *
     * @return the reason, or {@code null} when the attempt succeeded
     */
    String errorMessage();

    /**
     * Delivered.
     *
     * @param responseCode the status code the destination replied with, or {@code null} for
     *                     a channel that reports none
     */
    record Success(Integer responseCode) implements DispatchOutcome {

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.SUCCESS;
        }

        @Override
        public String errorMessage() {
            return null;
        }
    }

    /**
     * Failed for a reason that may not repeat, so the notification is rescheduled while it
     * still has attempts left.
     *
     * @param responseCode the status code the destination replied with, or {@code null} when
     *                     no response ever arrived, as with a timeout
     * @param errorMessage why the attempt failed; required, because a failure with no
     *                     recorded reason explains nothing later
     * @param retryAfter   the destination's own instruction on when to return, taken from the
     *                     {@code Retry-After} header. Null means it gave none, which is a
     *                     different statement from {@code Duration.ZERO}: that would mean
     *                     retry immediately
     */
    record RetryableFailure(Integer responseCode, String errorMessage, Duration retryAfter)
            implements DispatchOutcome {

        public RetryableFailure {
            errorMessage = requireReason(errorMessage);
            if (retryAfter != null && retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.RETRYABLE_FAILURE;
        }
    }

    /**
     * Rejected for a reason that retrying would not change.
     *
     * @param responseCode the status code the destination replied with, or {@code null} for a
     *                     channel that reports none
     * @param errorMessage why the attempt was rejected; required, because a failure with no
     *                     recorded reason explains nothing later
     */
    record PermanentFailure(Integer responseCode, String errorMessage) implements DispatchOutcome {

        public PermanentFailure {
            errorMessage = requireReason(errorMessage);
        }

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.PERMANENT_FAILURE;
        }
    }

    /**
     * A failure with no recorded reason cannot answer why a notification ended up
     * {@code FAILED}, which is the question the attempt history exists to answer.
     */
    private static String requireReason(String errorMessage) {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        if (errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return errorMessage;
    }
}
