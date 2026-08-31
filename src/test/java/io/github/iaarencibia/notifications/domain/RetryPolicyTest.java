package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Randomness enters through the constructor, never through a call to {@code Math.random()}
 * inside the calculation. A policy built with a fixed source produces exactly predictable
 * delays, which is what makes the backoff assertable without sleeping a test.
 */
class RetryPolicyTest {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final double MULTIPLIER = 3.0;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final double JITTER = 0.2;

    /** Production defaults, matching the {@code notifications.retry} block of application.yml. */
    private static RetryPolicy policyWithRandom(double fixedRandom) {
        return new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, JITTER, () -> fixedRandom);
    }

    @Nested
    @DisplayName("scheduled backoff")
    class ScheduledBackoff {

        @Test
        @DisplayName("grows geometrically from the initial delay")
        void growsGeometrically() {
            RetryPolicy policy = policyWithRandom(0.0);

            assertThat(policy.delayFor(1, null)).isEqualTo(Duration.ofSeconds(5));
            assertThat(policy.delayFor(2, null)).isEqualTo(Duration.ofSeconds(15));
            assertThat(policy.delayFor(3, null)).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("is capped at the configured maximum")
        void isCappedAtMaximum() {
            RetryPolicy policy = policyWithRandom(0.0);

            // 5s * 3^4 = 405s, above the 300s ceiling.
            assertThat(policy.delayFor(5, null)).isEqualTo(MAX_BACKOFF);
            assertThat(policy.delayFor(20, null)).isEqualTo(MAX_BACKOFF);
        }

        @Test
        @DisplayName("rejects an attempt number below one")
        void rejectsNonPositiveAttemptNumber() {
            RetryPolicy policy = policyWithRandom(0.0);

            assertThatIllegalArgumentException().isThrownBy(() -> policy.delayFor(0, null));
            assertThatIllegalArgumentException().isThrownBy(() -> policy.delayFor(-1, null));
        }
    }

    @Nested
    @DisplayName("Retry-After hint")
    class RetryAfterHint {

        @Test
        @DisplayName("replaces the scheduled backoff when the destination supplies one")
        void replacesTheSchedule() {
            // The destination said when to come back. Preferring our own number is how a 429
            // turns into a second 429.
            assertThat(policyWithRandom(0.0).delayFor(1, Duration.ofSeconds(30)))
                    .isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("is capped at the configured maximum, like any other delay")
        void isCappedAtMaximum() {
            // The hint arrives from a destination whose URL the client chose, so it is
            // untrusted input. Without a ceiling, a single header could park a notification
            // for a day.
            assertThat(policyWithRandom(0.0).delayFor(1, Duration.ofHours(24)))
                    .isEqualTo(MAX_BACKOFF);
        }

        @Test
        @DisplayName("receives jitter like any other delay, never returning before the hinted instant")
        void receivesJitter() {
            // Jitter only ever adds, so the result never precedes the instant the destination
            // asked for -- which is what Retry-After actually requires: not before, rather
            // than exactly at.
            assertThat(policyWithRandom(1.0).delayFor(1, Duration.ofSeconds(30)))
                    .isEqualTo(Duration.ofSeconds(36));
        }

        @Test
        @DisplayName("falls back to the scheduled backoff when absent")
        void fallsBackWhenAbsent() {
            assertThat(policyWithRandom(0.0).delayFor(2, null)).isEqualTo(Duration.ofSeconds(15));
        }

        @Test
        @DisplayName("honours a hint of zero as an instruction to retry immediately")
        void zeroMeansImmediately() {
            // Distinct from an absent hint. Note the maximum random value: jitter is a
            // fraction of the delay, so a zero delay receives no spreading whatsoever, and
            // every notification a destination answers this way becomes due at once. Two
            // separate limits bound the damage -- the attempt budget, per notification, and
            // the dispatcher's batch size, across all of them.
            assertThat(policyWithRandom(1.0).delayFor(1, Duration.ZERO)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("rejects a negative hint")
        void rejectsNegativeHint() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> policyWithRandom(0.0).delayFor(1, Duration.ofSeconds(-1)));
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        @Test
        @DisplayName("reproduces the worked example documented for the retry timeline")
        void reproducesTheDocumentedTimeline() {
            // First failure: 5s base, random 0.6 -> 5 * (1 + 0.6 * 0.2) = 5.6s
            assertThat(policyWithRandom(0.6).delayFor(1, null)).isEqualTo(Duration.ofMillis(5_600));

            // Second failure: 15s base, random 0.4 -> 15 * (1 + 0.4 * 0.2) = 16.2s
            assertThat(policyWithRandom(0.4).delayFor(2, null)).isEqualTo(Duration.ofMillis(16_200));
        }

        @Test
        @DisplayName("only ever adds to the delay, never subtracts")
        void onlyAddsToTheDelay() {
            assertThat(policyWithRandom(0.0).delayFor(1, null)).isEqualTo(INITIAL_BACKOFF);
            assertThat(policyWithRandom(1.0).delayFor(1, null)).isEqualTo(Duration.ofSeconds(6));
        }

        @Test
        @DisplayName("stays within the base delay and the base plus the jitter fraction")
        void staysWithinBounds() {
            DoubleSupplier walkingRandom = new DoubleSupplier() {
                private double next = 0.0;

                @Override
                public double getAsDouble() {
                    double value = next;
                    next = Math.min(next + 0.05, 1.0);
                    return value;
                }
            };
            RetryPolicy policy =
                    new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, JITTER, walkingRandom);

            for (int i = 0; i < 21; i++) {
                assertThat(policy.delayFor(1, null))
                        .isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
            }
        }

        @Test
        @DisplayName("the source production actually uses stays inside the band, and varies")
        void theProductionRandomSourceHonoursItsContract() {
            // Every other test in this class pins the random source, so nothing here would
            // notice if the default one returned values outside [0, 1): a delay shorter than
            // the base, or wider than the jitter permits. This is the only test that builds
            // the policy the way production builds it.
            RetryPolicy policy = new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, JITTER);
            Set<Duration> observed = new HashSet<>();

            for (int i = 0; i < 1_000; i++) {
                Duration delay = policy.delayFor(1, null);
                assertThat(delay).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
                observed.add(delay);
            }

            // A source stuck on a constant would satisfy the bound above while spreading
            // nothing at all, which is the failure the bound alone cannot see.
            assertThat(observed).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("still applies once the delay has hit the ceiling")
        void appliesAtTheCeiling() {
            // Without this, every retry that reached the ceiling would fire at the very same
            // instant -- precisely when spreading them out matters most, because a ceiling is
            // reached when a destination has been failing for a long time.
            assertThat(policyWithRandom(1.0).delayFor(10, null)).isEqualTo(Duration.ofSeconds(360));
            assertThat(policyWithRandom(0.0).delayFor(10, null)).isEqualTo(MAX_BACKOFF);
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class ConfigurationValidation {

        @Test
        @DisplayName("requires a positive initial backoff")
        void requiresPositiveInitialBackoff() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(Duration.ZERO, MULTIPLIER, MAX_BACKOFF, JITTER));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(Duration.ofSeconds(-1), MULTIPLIER, MAX_BACKOFF, JITTER));
        }

        @Test
        @DisplayName("requires an initial backoff of at least one millisecond")
        void requiresAtLeastOneMillisecond() {
            // Half a millisecond is neither negative nor zero, so a check written in those
            // terms lets it through -- and the calculation, which works in milliseconds,
            // then truncates it to nothing. The result is a policy that validates cleanly
            // and produces no backoff at all.
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(Duration.ofNanos(500_000), MULTIPLIER, MAX_BACKOFF, JITTER));
        }

        @Test
        @DisplayName("requires a multiplier of at least one, so the delay never shrinks")
        void requiresMultiplierOfAtLeastOne() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(INITIAL_BACKOFF, 0.9, MAX_BACKOFF, JITTER));
        }

        @Test
        @DisplayName("requires a ceiling no lower than the initial backoff")
        void requiresCoherentCeiling() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, Duration.ofSeconds(1), JITTER));
        }

        @Test
        @DisplayName("requires a jitter fraction between zero and one")
        void requiresJitterBetweenZeroAndOne() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, -0.1));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, 1.1));
        }

        @Test
        @DisplayName("accepts zero jitter, which disables spreading entirely")
        void acceptsZeroJitter() {
            RetryPolicy policy = new RetryPolicy(INITIAL_BACKOFF, MULTIPLIER, MAX_BACKOFF, 0.0);

            assertThat(policy.delayFor(1, null)).isEqualTo(INITIAL_BACKOFF);
        }
    }
}
