package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.domain.Notification;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
 * @param retry      the delivery budget a notification is created with
 */
@ConfigurationProperties(prefix = "notifications")
@Validated
public record NotificationsProperties(
        @NotBlank @Size(max = Notification.MAX_CLAIMED_BY_LENGTH) String instanceId,
        @NotNull @Valid Security security,
        @NotNull @Valid Retry retry) {

    /**
     * @param apiKey the shared key a caller presents to reach a protected endpoint
     */
    public record Security(@NotBlank String apiKey) {
    }

    /**
     * Only the budget is bound. The backoff, the multiplier, the ceiling and the jitter are read
     * by the retry policy, which the dispatcher wires in, and they join this record then.
     *
     * @param maxAttempts how many deliveries a notification is given. Stamped on each row as it
     *                    is created, so a later change of configuration leaves notifications
     *                    already in flight with the budget they were promised
     */
    public record Retry(@Min(1) int maxAttempts) {
    }
}
