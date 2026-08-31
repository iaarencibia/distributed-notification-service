package io.github.iaarencibia.notifications.domain;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Every outcome the domain can express must map onto a value the {@code ck_attempt_outcome}
 * check constraint accepts, and must carry enough detail to explain itself later: a failure
 * with no recorded reason cannot answer why a notification ended up {@code FAILED}.
 */
class DispatchOutcomeTest {

    @Nested
    @DisplayName("Success")
    class SuccessOutcome {

        @Test
        @DisplayName("records SUCCESS and carries no error")
        void recordsSuccess() {
            DispatchOutcome outcome = new DispatchOutcome.Success(200);

            assertThat(outcome.attemptOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(outcome.responseCode()).isEqualTo(200);
            assertThat(outcome.errorMessage()).isNull();
        }

        @Test
        @DisplayName("allows a null response code, for channels that have none")
        void allowsNullResponseCode() {
            // The LOG channel writes to stdout: it either succeeded or threw. There is no
            // status code to report, and inventing one would be a lie in the audit trail.
            DispatchOutcome outcome = new DispatchOutcome.Success(null);

            assertThat(outcome.attemptOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(outcome.responseCode()).isNull();
        }
    }

    @Nested
    @DisplayName("RetryableFailure")
    class RetryableFailureOutcome {

        @Test
        @DisplayName("records RETRYABLE_FAILURE with the reason it may be retried")
        void recordsRetryableFailure() {
            DispatchOutcome outcome =
                    new DispatchOutcome.RetryableFailure(503, "Service Unavailable", null);

            assertThat(outcome.attemptOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(outcome.responseCode()).isEqualTo(503);
            assertThat(outcome.errorMessage()).isEqualTo("Service Unavailable");
        }

        @Test
        @DisplayName("allows a null response code, for failures that never got a response")
        void allowsNullResponseCode() {
            // A read timeout or a refused connection is transient and must be retried, but
            // there is no HTTP status to record because no response ever arrived.
            DispatchOutcome outcome =
                    new DispatchOutcome.RetryableFailure(null, "Read timed out", null);

            assertThat(outcome.responseCode()).isNull();
            assertThat(outcome.attemptOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        }

        @Test
        @DisplayName("carries an optional Retry-After hint from the destination")
        void carriesRetryAfterHint() {
            DispatchOutcome.RetryableFailure outcome = new DispatchOutcome.RetryableFailure(
                    429, "Too Many Requests", Duration.ofSeconds(30));

            assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("treats a missing Retry-After as absent, not as zero")
        void missingRetryAfterIsNull() {
            // Zero would mean "retry immediately", which is a different instruction from
            // "the destination said nothing, use our own backoff".
            DispatchOutcome.RetryableFailure outcome =
                    new DispatchOutcome.RetryableFailure(503, "Service Unavailable", null);

            assertThat(outcome.retryAfter()).isNull();
        }

        @Test
        @DisplayName("rejects a negative Retry-After")
        void rejectsNegativeRetryAfter() {
            assertThatIllegalArgumentException().isThrownBy(() -> new DispatchOutcome.RetryableFailure(
                    429, "Too Many Requests", Duration.ofSeconds(-1)));
        }

        @Test
        @DisplayName("requires an error message")
        void requiresErrorMessage() {
            assertThatNullPointerException().isThrownBy(
                    () -> new DispatchOutcome.RetryableFailure(503, null, null));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new DispatchOutcome.RetryableFailure(503, "  ", null));
        }
    }

    @Nested
    @DisplayName("PermanentFailure")
    class PermanentFailureOutcome {

        @Test
        @DisplayName("records PERMANENT_FAILURE with the reason retrying is pointless")
        void recordsPermanentFailure() {
            DispatchOutcome outcome = new DispatchOutcome.PermanentFailure(400, "Bad Request");

            assertThat(outcome.attemptOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
            assertThat(outcome.responseCode()).isEqualTo(400);
            assertThat(outcome.errorMessage()).isEqualTo("Bad Request");
        }

        @Test
        @DisplayName("requires an error message")
        void requiresErrorMessage() {
            // A notification that ends in FAILED with no recorded reason cannot answer the
            // one question the attempt history exists to answer.
            assertThatNullPointerException().isThrownBy(
                    () -> new DispatchOutcome.PermanentFailure(400, null));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new DispatchOutcome.PermanentFailure(400, "  "));
        }
    }
}
