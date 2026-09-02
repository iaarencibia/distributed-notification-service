package io.github.iaarencibia.notifications.adapter.in.web;

import java.util.UUID;

/**
 * What a caller gets back for a notification the service accepted.
 *
 * <p>Only the identifier. The response is a {@code 202}, meaning the notification was taken and
 * will be attempted, so reporting a state alongside it would be describing something that has not
 * happened yet. The state is what the lookup endpoint is for.
 *
 * @param id identifies the notification for the rest of its life
 */
record SubmitNotificationResponse(UUID id) {
}
