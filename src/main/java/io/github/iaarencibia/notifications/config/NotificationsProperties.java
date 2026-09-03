package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The {@code notifications.*} configuration, bound and validated while the context is built.
 *
 * <p>Only the keys the application reads today are bound. The remaining subtrees declared in
 * {@code application.yml} join this record as the code that consumes them arrives, so that no
 * field exists here without a reader.
 *
 * @param instanceId identifies this instance, and is written to
 *                   {@code notification.claimed_by} when a row is claimed. The ceiling is
 *                   {@link Notification#MAX_CLAIMED_BY_LENGTH} rather than a number of its
 *                   own, so that configuration and domain cannot disagree about the width of
 *                   that column
 * @param security   the credentials the intake endpoint requires
 * @param dispatch   how often the worker looks for work, and how much of it runs at once
 * @param reaper     when a claim is taken to have outlived its owner
 * @param retry      the delivery budget a notification is created with, and the schedule its
 *                   retries follow
 * @param channels   how each delivery channel reaches the outside world
 */
@ConfigurationProperties(prefix = "notifications")
@Validated
public record NotificationsProperties(
        @NotBlank @Size(max = Notification.MAX_CLAIMED_BY_LENGTH) String instanceId,
        @NotNull @Valid Security security,
        @NotNull @Valid Dispatch dispatch,
        @NotNull @Valid Reaper reaper,
        @NotNull @Valid Retry retry,
        @NotNull @Valid Channels channels) {

    /**
     * @param apiKey the shared key a caller presents to reach a protected endpoint
     */
    public record Security(@NotBlank String apiKey) {
    }

    /**
     * Every value here bounds how much work one instance moves at a time, and a zero in any of
     * them fails the same silent way: the worker keeps running, logs nothing unusual, and
     * delivers nothing.
     *
     * @param pollInterval        how often the worker looks for claimable rows. It is the floor
     *                            on dispatch latency, and the price of not running a broker
     * @param batchSize           rows claimed per poll, per instance
     * @param workerPoolSize      deliveries one instance performs at once. Bounded on purpose:
     *                            an unbounded pool turns a slow destination into unbounded
     *                            memory growth
     * @param workerQueueCapacity claimed rows that may wait for a free worker
     */
    public record Dispatch(@NotNull Duration pollInterval, @Min(1) int batchSize,
            @Min(1) int workerPoolSize, @Min(1) int workerQueueCapacity) {

        public Dispatch {
            requirePositive(pollInterval, "pollInterval");
        }
    }

    /**
     * @param staleClaimTimeout how long a row may stay DISPATCHING before its owner is assumed
     *                          dead. It is the bound on duplicate delivery: too short and the
     *                          reaper releases rows still being dispatched
     * @param interval          how often that sweep runs
     */
    public record Reaper(@NotNull Duration staleClaimTimeout, @NotNull Duration interval) {

        public Reaper {
            requirePositive(staleClaimTimeout, "staleClaimTimeout");
            requirePositive(interval, "interval");
        }
    }

    /**
     * The budget is bounded here; the schedule is not, and that is deliberate.
     * {@link RetryPolicy} validates its own four values and is built while the context starts,
     * so an impossible backoff already refuses startup -- at the same moment, with the rule
     * owned by the class that applies it. Repeating it here is what was removed from
     * {@code RetryPolicy} in the first place.
     *
     * @param maxAttempts    how many deliveries a notification is given. Stamped on each row as
     *                       it is created, so a later change of configuration leaves
     *                       notifications already in flight with the budget they were promised
     * @param initialBackoff the delay after the first failure
     * @param multiplier     the factor each successive delay grows by
     * @param maxBackoff     the ceiling every delay is capped at, a destination's own
     *                       {@code Retry-After} included
     * @param jitter         the fraction of a delay that may be added on top, so that retries
     *                       from many rows do not align
     */
    public record Retry(@Min(1) int maxAttempts, @NotNull Duration initialBackoff,
            double multiplier, @NotNull Duration maxBackoff, double jitter) {
    }

    /**
     * Only the SERVICE channel is configurable, because it is the only one that leaves this
     * process. LOG has nothing to configure, and EMAIL joins this record with its adapter.
     *
     * @param service how the outbound HTTP channel is bounded
     */
    public record Channels(@NotNull @Valid Service service) {

        /**
         * Both timeouts are required and must be positive, and that is the point of binding them
         * at all. A client with no read timeout waits forever on a destination that accepted the
         * connection and then went quiet, and one worker thread is lost for as long as that
         * lasts -- which, with a bounded pool, is how a single slow destination stops every
         * other notification in the queue.
         *
         * @param connectTimeout how long to wait for the destination to accept a connection
         * @param readTimeout    how long to wait for its answer once connected
         */
        public record Service(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

            public Service {
                requirePositive(connectTimeout, "connectTimeout");
                requirePositive(readTimeout, "readTimeout");
            }
        }
    }

    /**
     * Shared by every block that configures a duration, so the rule is written once.
     *
     * @param value the configured duration, possibly absent
     * @param name  the property to name if it is unusable
     */
    private static void requirePositive(Duration value, String name) {
        // A null is left alone on purpose: @NotNull owns the absent case and reports it by the
        // property name, which is the message an operator can act on.
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
