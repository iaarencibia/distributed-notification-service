package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One dispatch attempt, as it happened. Produced by the aggregate and persisted by the
 * application service: keeping it outside the aggregate is what stops the worker from
 * loading the whole history every time it claims a row.
 *
 * @param id             identity of this attempt
 * @param notificationId the notification it belongs to
 * @param attemptNumber  which attempt this was, counting from one
 * @param channel        the mechanism the delivery was made through
 * @param outcome        how it ended, in the form the row stores
 * @param responseCode   the status code the destination replied with, or {@code null} when
 *                       there is none to report
 * @param errorMessage   why it failed, or {@code null} when it succeeded. Required on a
 *                       failure, mirroring the ck_attempt_failure_has_reason constraint
 * @param startedAt      when the attempt began
 * @param finishedAt     when it ended; never before it began
 */
public record NotificationAttempt(UUID id, UUID notificationId, int attemptNumber, Channel channel,
        AttemptOutcome outcome, Integer responseCode, String errorMessage,
        Instant startedAt, Instant finishedAt) {

    public NotificationAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        // Mirrors ck_attempt_failure_has_reason. The rule lives in both places for the same
        // reason the state machine does: the row outlives the object that created it, and a
        // reader asking why a notification failed has nothing else to go on.
        if (outcome != AttemptOutcome.SUCCESS && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("a failed attempt must record why it failed");
        }
    }

    /**
     * Derived rather than stored as a component, so that it cannot disagree with the two
     * instants it is measured from.
     *
     * @return how long the attempt took, in milliseconds; zero when it finished within the
     *         resolution of the clock
     */
    public long durationMs() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
