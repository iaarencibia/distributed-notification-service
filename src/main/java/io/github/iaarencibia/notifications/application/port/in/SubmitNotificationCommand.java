package io.github.iaarencibia.notifications.application.port.in;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.NotificationPayload;

/**
 * A request to send a notification, already in the language of the domain.
 *
 * <p>There is no HTTP in here on purpose: the web adapter has translated its DTO and its headers
 * into these three values before this type exists, so nothing further in reads a header name or a
 * status code. A second entry point -- a scheduled job, a message consumer -- would build the same
 * command without any of the intake endpoint coming along with it.
 *
 * @param payload        what to send, and to whom
 * @param correlationId  groups this notification with the rest of its operation. Never null: the
 *                       adapter generates one when the caller supplies none, because the column
 *                       is NOT NULL and the grouping is worthless with holes in it
 * @param idempotencyKey the caller's own key, or {@code null} when it sent none. Optional because
 *                       only the caller knows whether two requests are the same logical send
 */
public record SubmitNotificationCommand(NotificationPayload payload, CorrelationId correlationId,
        String idempotencyKey) {
}
