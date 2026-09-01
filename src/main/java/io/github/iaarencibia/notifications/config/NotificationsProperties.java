package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.domain.Notification;
import jakarta.validation.Valid;
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
 */
@ConfigurationProperties(prefix = "notifications")
@Validated
public record NotificationsProperties(
        @NotBlank @Size(max = Notification.MAX_CLAIMED_BY_LENGTH) String instanceId,
        @NotNull @Valid Security security) {

    /**
     * @param apiKey the shared key a caller presents to reach a protected endpoint
     */
    public record Security(@NotBlank String apiKey) {
    }
}
