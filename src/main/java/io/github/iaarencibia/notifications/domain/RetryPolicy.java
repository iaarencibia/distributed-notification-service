package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides how long a notification waits before its next attempt.
 *
 * <p>The source of randomness is a constructor argument rather than a call to
 * {@code Math.random()} inside the calculation. A policy built with a fixed source produces
 * exactly predictable delays, which is what lets the backoff be asserted without sleeping.
 */
public final class RetryPolicy {

    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;
    private final double jitter;
    private final DoubleSupplier randomSource;

    public RetryPolicy(Duration initialBackoff, double multiplier,
            Duration maxBackoff, double jitter) {
        this(initialBackoff, multiplier, maxBackoff, jitter,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param initialBackoff the delay after the first failure, and the base every later one
     *                       grows from
     * @param multiplier     the factor each successive delay is multiplied by
     * @param maxBackoff     the ceiling every delay is capped at, a destination's hint
     *                       included
     * @param jitter         the fraction of a delay that may be added on top of it
     * @param randomSource   supplies that fraction; must yield values in {@code [0, 1)}
     */
    public RetryPolicy(Duration initialBackoff, double multiplier,
            Duration maxBackoff, double jitter, DoubleSupplier randomSource) {

        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource must not be null");

        // Expressed in milliseconds, not merely "positive", because that is the unit the
        // calculation works in: anything below one would truncate to zero and leave the
        // policy validating cleanly while producing no backoff at all.
        if (initialBackoff.toMillis() < 1) {
            throw new IllegalArgumentException("initialBackoff must be at least 1 millisecond");
        }
        // Below 1 the delay would shrink with every failure, hammering a destination that is
        // already failing.
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be below initialBackoff");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
        }

        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
        this.jitter = jitter;
    }

    /**
     * How long to wait before attempt {@code attemptNumber + 1}, in this precedence:
     *
     * <ol>
     * <li>{@code retryAfterHint}, when the destination supplied one; otherwise the geometric
     * schedule. A destination that states when it will be ready knows something this policy
     * cannot infer.</li>
     * <li>capped at {@code maxBackoff}, whichever of the two produced the value. The hint
     * arrives from an address the client chose, so it is untrusted input.</li>
     * <li>spread by jitter, which is a fraction of the delay and only ever adds, so the
     * result never precedes the instant a hint asked for. Being a fraction, it spreads
     * nothing at all when the delay is zero and very little when it is small: what bounds a
     * burst of simultaneously due notifications is the dispatcher's batch size, not this.</li>
     * </ol>
     *
     * @param attemptNumber  the attempt that has just finished, counting from one
     * @param retryAfterHint the destination's own instruction, or {@code null} if it gave
     *                       none. {@code Duration.ZERO} is not the same thing: it means
     *                       retry immediately
     * @return how long to wait before making the next attempt
     */
    public Duration delayFor(int attemptNumber, Duration retryAfterHint) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        if (retryAfterHint != null && retryAfterHint.isNegative()) {
            throw new IllegalArgumentException("retryAfterHint must not be negative");
        }

        Duration base = retryAfterHint != null ? retryAfterHint : scheduledBackoff(attemptNumber);
        if (base.compareTo(maxBackoff) > 0) {
            base = maxBackoff;
        }
        return applyJitter(base);
    }

    /**
     * Capped before the conversion back to a {@code Duration}, so that a high attempt number
     * cannot overflow the multiplication.
     */
    private Duration scheduledBackoff(int attemptNumber) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attemptNumber - 1);
        if (millis >= maxBackoff.toMillis()) {
            return maxBackoff;
        }
        return Duration.ofMillis((long) millis);
    }

    /** Rounded rather than truncated, so that 5000 x 1.12 is 5600 and not 5599. */
    private Duration applyJitter(Duration base) {
        double factor = 1.0 + randomSource.getAsDouble() * jitter;
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
