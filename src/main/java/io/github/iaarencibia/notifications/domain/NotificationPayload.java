package io.github.iaarencibia.notifications.domain;

import java.util.Map;
import java.util.Objects;

/**
 * The client-supplied half of a notification, grouped as the schema groups it.
 *
 * <p>Bean Validation on the inbound DTO rejects a malformed request with a descriptive 400
 * long before this type is reached. These invariants are the second line, and they hold for
 * every caller -- including one that never passes through HTTP.
 *
 * @param recipient where the notification is delivered: a URL for the SERVICE channel, an
 *                  address for the others
 * @param channel   the mechanism it is delivered through, which selects the adapter
 * @param subject   a short heading, bounded by the width of its column
 * @param body      the content; may be empty, since a subject-only ping is legitimate
 * @param priority  how urgently it should be dispatched
 * @param metadata  arbitrary client data, carried through untouched. Absent becomes an empty
 *                  map rather than null, matching the column default
 */
public record NotificationPayload(String recipient, Channel channel, String subject,
        String body, Priority priority, Map<String, String> metadata) {

    /**
     * Mirrors the width of {@code notification.recipient}. Public so that the inbound DTO can
     * declare the same limit without writing the number a third time.
     */
    public static final int MAX_RECIPIENT_LENGTH = 2048;

    /** Mirrors the width of {@code notification.subject}. */
    public static final int MAX_SUBJECT_LENGTH = 512;

    public NotificationPayload {
        recipient = requireText(recipient, "recipient", MAX_RECIPIENT_LENGTH);
        subject = requireText(subject, "subject", MAX_SUBJECT_LENGTH);

        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        // Unbounded, matching the TEXT column, and allowed to be empty: a subject-only ping
        // is a legitimate notification.
        Objects.requireNonNull(body, "body must not be null");

        // Copies, forbids nulls and returns something unmodifiable, all in one call. Absent
        // metadata becomes the empty map rather than null, matching DEFAULT '{}'::JSONB.
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        // Counted in characters, not in UTF-16 units, because that is how the column counts:
        // an emoji is one character for PostgreSQL and two for String.length(). Measuring in
        // code units would reject a value the column accepts, quoting back a limit the caller
        // has not exceeded.
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }
}
