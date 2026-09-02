package io.github.iaarencibia.notifications.adapter.in.web;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * The body of a request to send a notification.
 *
 * <p>Separate from {@link NotificationPayload} even though the fields line up today, because the
 * two answer to different owners: this one to whatever the published API promised its callers,
 * that one to the invariants of the domain. Merging them would mean a rename in the domain
 * silently breaking every client.
 *
 * <p>Every limit here is the domain's public constant rather than a number typed again, and it
 * is measured the way the domain and the column measure it -- in characters, which is what
 * {@link CharacterLength} exists for. Sharing the number without sharing the unit was enough to
 * make the two layers disagree over anything outside the basic plane.
 *
 * <p>The domain checks the same things a second time on its own behalf: it has callers that
 * never came through HTTP.
 *
 * @param recipient where to deliver: a URL for the SERVICE channel, an address for the others
 * @param channel   how to deliver it, which is what selects the adapter
 * @param subject   the heading
 * @param body      the content. Allowed to be empty -- a subject-only ping is a real
 *                  notification -- but not absent, so its omission is never silent
 * @param priority  how urgently it should be dispatched
 * @param metadata  arbitrary caller data, carried through untouched. Optional, and absent is
 *                  read as none rather than rejected
 */
record SubmitNotificationRequest(

        @NotBlank(message = "must not be blank")
        @CharacterLength(max = NotificationPayload.MAX_RECIPIENT_LENGTH)
        String recipient,

        @NotNull(message = "must be one of LOG, SERVICE, EMAIL")
        Channel channel,

        @NotBlank(message = "must not be blank")
        @CharacterLength(max = NotificationPayload.MAX_SUBJECT_LENGTH)
        String subject,

        @NotNull(message = "must be present, though it may be empty")
        String body,

        @NotNull(message = "must be one of LOW, MEDIUM, HIGH")
        Priority priority,

        Map<String, @NotNull(message = "must not be null") String> metadata) {

    NotificationPayload toPayload() {
        return new NotificationPayload(recipient, channel, subject, body, priority, metadata);
    }
}
