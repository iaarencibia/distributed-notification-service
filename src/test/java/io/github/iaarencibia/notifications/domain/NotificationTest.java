package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate owns the legal transitions, the attempt budget and the record of every
 * attempt -- not the schedule, which the claim query enforces. It is exercised here without a
 * Spring
 * context, a database, or a clock that moves on its own. Time and randomness are always
 * arguments, never ambient state, which is what makes these assertions exact instead of
 * approximate.
 */
class NotificationTest {

    private static final UUID ID = UUID.fromString("3f2a1c40-0000-4000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-29T10:00:00Z");
    private static final String INSTANCE = "notification-service-3";
    private static final CorrelationId CORRELATION_ID = new CorrelationId("5c1b9e2a-trace");

    private static final int MAX_ATTEMPTS = 3;

    /**
     * The random source is pinned to zero, not the jitter fraction, so the expected delays are
     * exact. Jitter itself is covered by RetryPolicyTest.
     */
    private static final RetryPolicy POLICY = new RetryPolicy(
            Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2, () -> 0.0);

    private static NotificationPayload payload() {
        return new NotificationPayload("https://destination.test/hook", Channel.SERVICE,
                "Deployment finished", "Version 0.1.0 is live.", Priority.HIGH,
                Map.of("source", "ci"));
    }

    private static Notification submitted() {
        return submitted(MAX_ATTEMPTS);
    }

    private static Notification submitted(int maxAttempts) {
        return Notification.submit(ID, payload(), CORRELATION_ID, "idem-key-1", maxAttempts, T0);
    }

    /** Claims the notification and reports a retryable failure, the way the worker would. */
    private static NotificationAttempt failRetryably(Notification notification, Instant at) {
        notification.claim(at, INSTANCE);
        return notification.recordAttempt(
                new DispatchOutcome.RetryableFailure(503, "Service Unavailable", null),
                at,
                at.plusMillis(50),
                POLICY);
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        @DisplayName("starts PENDING and immediately claimable")
        void startsPendingAndClaimable() {
            Notification notification = submitted();

            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.attempts()).isZero();
            assertThat(notification.maxAttempts()).isEqualTo(3);

            // next_attempt_at = now means the very next poll picks it up. Any later value
            // would add latency that nothing asked for.
            assertThat(notification.nextAttemptAt()).isEqualTo(T0);
            assertThat(notification.createdAt()).isEqualTo(T0);
            assertThat(notification.updatedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("carries no dispatch history yet")
        void carriesNoHistoryYet() {
            Notification notification = submitted();

            assertThat(notification.sentAt()).isNull();
            assertThat(notification.lastError()).isNull();
            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("keeps the payload and the trace identifier it was submitted with")
        void keepsPayloadAndCorrelationId() {
            Notification notification = submitted();

            assertThat(notification.id()).isEqualTo(ID);
            assertThat(notification.payload()).isEqualTo(payload());
            assertThat(notification.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(notification.idempotencyKey()).isEqualTo("idem-key-1");
        }

        @Test
        @DisplayName("accepts a missing idempotency key")
        void acceptsMissingIdempotencyKey() {
            // Only the caller knows whether two requests are the same logical send. Forcing
            // a key would make indifferent callers generate a fresh one per call, which
            // removes the protection while appearing to provide it.
            Notification notification =
                    Notification.submit(ID, payload(), CORRELATION_ID, null, 3, T0);

            assertThat(notification.idempotencyKey()).isNull();
        }

        @Test
        @DisplayName("rejects a blank idempotency key, which is not the same as none")
        void rejectsBlankIdempotencyKey() {
            // An empty string is NOT NULL, so the partial unique index stores it as a real
            // key. Two unrelated callers that both send an empty header would collide, and
            // the second notification would be returned as a duplicate of the first and
            // never delivered. Accepting it silently would hand a caller retry protection
            // it does not have.
            assertThatIllegalArgumentException().isThrownBy(
                    () -> Notification.submit(ID, payload(), CORRELATION_ID, "", 3, T0));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> Notification.submit(ID, payload(), CORRELATION_ID, "   ", 3, T0));
        }

        @Test
        @DisplayName("accepts an idempotency key at the column width and rejects one over")
        void idempotencyKeyBoundary() {
            Notification accepted =
                    Notification.submit(ID, payload(), CORRELATION_ID, "x".repeat(255), 3, T0);

            assertThat(accepted.idempotencyKey()).hasSize(255);
            assertThatIllegalArgumentException().isThrownBy(
                    () -> Notification.submit(ID, payload(), CORRELATION_ID, "x".repeat(256), 3, T0));
        }

        @Test
        @DisplayName("requires an attempt budget of at least one")
        void requiresAtLeastOneAttempt() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> Notification.submit(ID, payload(), CORRELATION_ID, null, 0, T0));
        }

        @Test
        @DisplayName("requires an id, a payload, a correlation id and a timestamp")
        void requiresMandatoryArguments() {
            assertThatNullPointerException().isThrownBy(
                    () -> Notification.submit(null, payload(), CORRELATION_ID, null, 3, T0));
            assertThatNullPointerException().isThrownBy(
                    () -> Notification.submit(ID, null, CORRELATION_ID, null, 3, T0));
            assertThatNullPointerException().isThrownBy(
                    () -> Notification.submit(ID, payload(), null, null, 3, T0));
            assertThatNullPointerException().isThrownBy(
                    () -> Notification.submit(ID, payload(), CORRELATION_ID, null, 3, null));
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("moves to DISPATCHING and records who owns the work")
        void recordsOwnership() {
            Notification notification = submitted();

            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThat(notification.status()).isEqualTo(NotificationStatus.DISPATCHING);
            assertThat(notification.claimedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(notification.claimedBy()).isEqualTo(INSTANCE);
            assertThat(notification.updatedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("does not consume an attempt")
        void doesNotConsumeAnAttempt() {
            // Attempts count deliveries tried, not rows claimed. A claim that the reaper
            // later reverses must not cost the notification part of its budget.
            Notification notification = submitted();

            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThat(notification.attempts()).isZero();
        }

        @Test
        @DisplayName("cannot claim a notification that is already claimed")
        void cannotClaimTwice() {
            Notification notification = submitted();
            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThatThrownBy(() -> notification.claim(T0.plusSeconds(2), "notification-service-7"))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("cannot claim a notification in a terminal state")
        void cannotClaimTerminal() {
            Notification sent = submitted();
            sent.claim(T0, INSTANCE);
            sent.recordAttempt(new DispatchOutcome.Success(200), T0, T0.plusMillis(40), POLICY);

            assertThatThrownBy(() -> sent.claim(T0.plusSeconds(1), INSTANCE))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("requires a non-blank owner, because the reaper depends on it")
        void requiresOwner() {
            Notification notification = submitted();

            assertThatNullPointerException().isThrownBy(() -> notification.claim(T0, null));
            assertThatIllegalArgumentException().isThrownBy(() -> notification.claim(T0, "  "));
        }

        @Test
        @DisplayName("accepts an owner at the column width and rejects one over")
        void claimedByBoundary() {
            // The owner is the instance id, which comes from configuration. Rejected here it
            // is one clear domain error; accepted, it would fail inside every claim UPDATE
            // with a driver error and stop dispatch altogether.
            Notification notification = submitted();
            notification.claim(T0, "x".repeat(128));

            assertThat(notification.claimedBy()).hasSize(128);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> submitted().claim(T0, "x".repeat(129)));
        }
    }

    @Nested
    @DisplayName("recording a successful attempt")
    class SuccessfulAttempt {

        @Test
        @DisplayName("moves to SENT and stamps the delivery time")
        void movesToSent() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.sentAt()).isEqualTo(T0.plusMillis(39));
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isNull();
        }

        @Test
        @DisplayName("releases the claim, so no row stays owned once it is done")
        void releasesTheClaim() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY);

            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("produces an attempt record with everything the audit trail needs")
        void producesAttemptRecord() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            NotificationAttempt attempt = notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY);

            assertThat(attempt.id()).isNotNull();
            assertThat(attempt.notificationId()).isEqualTo(ID);
            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.channel()).isEqualTo(Channel.SERVICE);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(attempt.responseCode()).isEqualTo(200);
            assertThat(attempt.errorMessage()).isNull();
            assertThat(attempt.startedAt()).isEqualTo(T0);
            assertThat(attempt.finishedAt()).isEqualTo(T0.plusMillis(39));
            assertThat(attempt.durationMs()).isEqualTo(39L);
        }
    }

    @Nested
    @DisplayName("recording a permanent failure")
    class PermanentFailureAttempt {

        @Test
        @DisplayName("fails immediately without spending the remaining attempts")
        void failsWithoutRetrying() {
            // This is the distinction the whole retry design exists for. A 400 will still be
            // a 400 on the third try; retrying it burns resources and changes nothing.
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.PermanentFailure(400, "Bad Request"), T0, T0.plusMillis(12), POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isEqualTo("Bad Request");
            assertThat(notification.sentAt()).isNull();
        }

        @Test
        @DisplayName("releases the claim")
        void releasesTheClaim() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.PermanentFailure(404, "Not Found"), T0, T0.plusMillis(12), POLICY);

            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("recording a retryable failure")
    class RetryableFailureAttempt {

        @Test
        @DisplayName("returns to PENDING scheduled by the backoff, counted from when the attempt ended")
        void reschedulesWithBackoff() {
            Notification notification = submitted();

            failRetryably(notification, T0);

            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isEqualTo("Service Unavailable");
            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(5));
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("grows the delay with each failure")
        void growsTheDelay() {
            Notification notification = submitted();

            failRetryably(notification, T0);
            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(5));

            Instant second = T0.plusSeconds(6);
            failRetryably(notification, second);
            assertThat(notification.nextAttemptAt()).isEqualTo(second.plusMillis(50).plusSeconds(15));
        }

        @Test
        @DisplayName("honours a Retry-After hint instead of the calculated backoff")
        void honoursRetryAfter() {
            // The destination told us when to come back. Ignoring it in favour of our own
            // number is how a 429 turns into a second 429.
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.RetryableFailure(429, "Too Many Requests", Duration.ofSeconds(30)),
                    T0,
                    T0.plusMillis(50),
                    POLICY);

            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(30));
        }

        @Test
        @DisplayName("numbers the attempts consecutively from one")
        void numbersAttemptsConsecutively() {
            Notification notification = submitted();

            assertThat(failRetryably(notification, T0).attemptNumber()).isEqualTo(1);
            assertThat(failRetryably(notification, T0.plusSeconds(6)).attemptNumber()).isEqualTo(2);
            assertThat(failRetryably(notification, T0.plusSeconds(22)).attemptNumber()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the attempt budget boundary")
    class AttemptBudgetBoundary {

        @Test
        @DisplayName("succeeding on the last allowed attempt ends in SENT, not FAILED")
        void successOnTheLastAttemptIsStillSuccess() {
            // The exact edge where a `>` written instead of a `>=` marks a delivered
            // notification as failed. It is the reason the flaky stub recovers on its
            // third call rather than its second.
            Notification notification = submitted(3);

            failRetryably(notification, T0);
            failRetryably(notification, T0.plusSeconds(6));

            Instant third = T0.plusSeconds(22);
            notification.claim(third, INSTANCE);
            notification.recordAttempt(
                    new DispatchOutcome.Success(200), third, third.plusMillis(39), POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.attempts()).isEqualTo(3);
            assertThat(notification.sentAt()).isEqualTo(third.plusMillis(39));
        }

        @Test
        @DisplayName("exhausting the budget ends in FAILED with the last error kept")
        void exhaustingTheBudgetFails() {
            Notification notification = submitted(3);

            failRetryably(notification, T0);
            failRetryably(notification, T0.plusSeconds(6));
            failRetryably(notification, T0.plusSeconds(22));

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(3);
            assertThat(notification.lastError()).isEqualTo("Service Unavailable");
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("a budget of one leaves no room for a retry")
        void singleAttemptBudget() {
            Notification notification = submitted(1);

            failRetryably(notification, T0);

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("the final failing attempt is still recorded in the history")
        void finalAttemptIsStillRecorded() {
            // "Why did this end up FAILED?" has to be answerable from the attempt table
            // alone, which means the attempt that exhausted the budget cannot be dropped.
            Notification notification = submitted(1);

            NotificationAttempt attempt = failRetryably(notification, T0);

            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.errorMessage()).isEqualTo("Service Unavailable");
        }
    }

    @Nested
    @DisplayName("guarding the transition")
    class TransitionGuards {

        @Test
        @DisplayName("cannot record an attempt on a notification that was never claimed")
        void cannotRecordWithoutClaiming() {
            // Recording a result for a row nobody owns means two instances dispatched it.
            Notification notification = submitted();

            assertThatThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("cannot record an attempt on a notification already in a terminal state")
        void cannotRecordOnTerminal() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);
            notification.recordAttempt(new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY);

            assertThatThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0, T0.plusMillis(39), POLICY))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("rejects an attempt that finished before it started")
        void rejectsInvertedTimestamps() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            assertThatIllegalArgumentException().isThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0.plusMillis(39), T0, POLICY));
        }

        @Test
        @DisplayName("requires an outcome, timestamps and a retry policy")
        void requiresMandatoryArguments() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            assertThatNullPointerException().isThrownBy(
                    () -> notification.recordAttempt(null, T0, T0.plusMillis(39), POLICY));
            assertThatNullPointerException().isThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), null, T0.plusMillis(39), POLICY));
            assertThatNullPointerException().isThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0, null, POLICY));
            assertThatNullPointerException().isThrownBy(() -> notification.recordAttempt(
                    new DispatchOutcome.Success(200), T0, T0.plusMillis(39), null));
        }
    }
}
