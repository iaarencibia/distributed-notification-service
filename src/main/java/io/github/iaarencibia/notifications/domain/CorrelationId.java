package io.github.iaarencibia.notifications.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Groups every notification produced by one logical operation. Several notifications share
 * one, which is why its index is not unique -- the difference with {@code idempotency_key},
 * whose index is.
 *
 * <p>A type rather than a {@code String} for two reasons. The value is validated once here
 * instead of at each of the boundaries it crosses: the endpoint, the aggregate, the
 * persistence mapper, every log line and the query endpoint. And it cannot be passed where an
 * idempotency key is expected, a mistake that would otherwise compile and silently reject the
 * second and third notification of an operation as duplicates.
 *
 * @param value the identifier itself, as the client supplied it or as generated when it
 *              supplied none
 */
public record CorrelationId(String value) {

    /** Mirrors the width of {@code notification.correlation_id}. */
    public static final int MAX_LENGTH = 128;

    /**
     * Restricted to what identifiers actually need. The value is written into a structured
     * log line on every attempt, so a newline in it would let a caller append entries of its
     * own.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]+");

    public CorrelationId {
        Objects.requireNonNull(value, "correlationId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "correlationId may only contain letters, digits and the characters . _ : -");
        }
        // Checked after the character set, which has already ruled out anything outside the
        // basic plane -- so here one UTF-16 unit is one character and no code point count is
        // needed. The order also gives a rejected value the message that actually explains it.
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "correlationId must not exceed " + MAX_LENGTH + " characters");
        }
    }

    /**
     * For callers that supply none, so the column is never without a value.
     *
     * @return a fresh identifier, distinct from every other
     */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
