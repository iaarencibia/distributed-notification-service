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
 * @param body      the content, bounded by the width of its column; may be empty, since a
 *                  subject-only ping is legitimate
 * @param priority  how urgently it should be dispatched
 * @param metadata  arbitrary client data, carried through untouched but not unbounded: a
 *                  limited number of entries, each with a bounded key and value. Absent becomes
 *                  an empty map rather than null, matching the column default
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

    /** Mirrors the width of {@code notification.body}. */
    public static final int MAX_BODY_LENGTH = 16384;

    /**
     * How many entries {@code metadata} may carry. The brief calls these "additional optional
     * fields", and a caller tagging a notification uses a handful; past this many the map has
     * stopped being fields alongside the notification and become a payload travelling beside it.
     */
    public static final int MAX_METADATA_ENTRIES = 32;

    /** A metadata key is an identifier, and this is the width {@code correlation_id} already uses. */
    public static final int MAX_METADATA_KEY_LENGTH = 128;

    /**
     * A metadata value is a label rather than content -- content is what {@code body} is for.
     * It mirrors {@link #MAX_RECIPIENT_LENGTH} so that a URL this service accepts as a recipient
     * is not refused as a value, which would be the same text allowed in one field and turned
     * away in another for no reason a caller could work out.
     */
    public static final int MAX_METADATA_VALUE_LENGTH = MAX_RECIPIENT_LENGTH;

    public NotificationPayload {
        recipient = requireText(recipient, "recipient", MAX_RECIPIENT_LENGTH);
        subject = requireText(subject, "subject", MAX_SUBJECT_LENGTH);

        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        // Not requireText: the body is bounded like the others but may be empty, since a
        // subject-only ping is a legitimate notification. Absent is still refused, so its
        // omission is never silent.
        Objects.requireNonNull(body, "body must not be null");
        requireWithin(body, "body", MAX_BODY_LENGTH);

        // Copies, forbids nulls and returns something unmodifiable, all in one call. Absent
        // metadata becomes the empty map rather than null, matching DEFAULT '{}'::JSONB.
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        requireBoundedMetadata(metadata);
    }

    private static void requireBoundedMetadata(Map<String, String> metadata) {
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                    "metadata must not exceed " + MAX_METADATA_ENTRIES + " entries");
        }
        metadata.forEach((key, value) -> {
            requireWithin(key, "metadata key", MAX_METADATA_KEY_LENGTH);
            // Named with the key it belongs to, because a caller holding thirty-two of them
            // needs to know which one to shorten.
            requireWithin(value, "metadata value for '" + key + "'", MAX_METADATA_VALUE_LENGTH);
        });
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return requireWithin(value, field, maxLength);
    }

    /**
     * Counted in characters, not in UTF-16 units, because that is how the column counts: an emoji
     * is one character for PostgreSQL and two for {@code String.length()}. Measuring in code units
     * would reject a value the column accepts, quoting back a limit the caller has not exceeded.
     */
    private static String requireWithin(String value, String field, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }
}
