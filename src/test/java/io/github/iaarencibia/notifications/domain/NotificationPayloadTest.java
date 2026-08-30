package io.github.iaarencibia.notifications.domain;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client-supplied half of a notification, grouped as the schema groups it.
 *
 * <p>Bean Validation on the inbound DTO rejects a bad request with a descriptive 400 long
 * before this type is reached. These invariants are the second line: they hold for every
 * caller, including a future scheduler or batch importer that never passes through HTTP. The
 * maximum lengths mirror the column widths, so an invalid payload fails here with a clear
 * message instead of failing at INSERT time with a driver error.
 */
class NotificationPayloadTest {

    private static final String RECIPIENT = "https://destination.test/hook";
    private static final String SUBJECT = "Deployment finished";
    private static final String BODY = "Version 0.1.0 is live.";

    private static NotificationPayload valid() {
        return new NotificationPayload(RECIPIENT, Channel.SERVICE, SUBJECT, BODY,
                Priority.HIGH, Map.of("source", "ci"));
    }

    private static NotificationPayload withRecipient(String recipient) {
        return new NotificationPayload(recipient, Channel.SERVICE, SUBJECT, BODY, Priority.HIGH, null);
    }

    private static NotificationPayload withSubject(String subject) {
        return new NotificationPayload(RECIPIENT, Channel.SERVICE, subject, BODY, Priority.HIGH, null);
    }

    private static NotificationPayload withBody(String body) {
        return new NotificationPayload(RECIPIENT, Channel.SERVICE, SUBJECT, body, Priority.HIGH, null);
    }

    private static NotificationPayload withMetadata(Map<String, String> metadata) {
        return new NotificationPayload(RECIPIENT, Channel.SERVICE, SUBJECT, BODY, Priority.HIGH, metadata);
    }

    @Nested
    @DisplayName("required fields")
    class RequiredFields {

        @Test
        @DisplayName("accepts a fully populated payload")
        void acceptsValidPayload() {
            NotificationPayload payload = valid();

            assertThat(payload.recipient()).isEqualTo(RECIPIENT);
            assertThat(payload.channel()).isEqualTo(Channel.SERVICE);
            assertThat(payload.subject()).isEqualTo(SUBJECT);
            assertThat(payload.body()).isEqualTo(BODY);
            assertThat(payload.priority()).isEqualTo(Priority.HIGH);
            assertThat(payload.metadata()).containsExactlyEntriesOf(Map.of("source", "ci"));
        }

        @Test
        @DisplayName("rejects a blank recipient")
        void rejectsBlankRecipient() {
            assertThatIllegalArgumentException().isThrownBy(() -> withRecipient("   "));
            assertThatNullPointerException().isThrownBy(() -> withRecipient(null));
        }

        @Test
        @DisplayName("rejects a blank subject")
        void rejectsBlankSubject() {
            assertThatIllegalArgumentException().isThrownBy(() -> withSubject(""));
            assertThatNullPointerException().isThrownBy(() -> withSubject(null));
        }

        @Test
        @DisplayName("rejects a null body but accepts an empty one")
        void bodyMayBeEmptyButNotNull() {
            // An empty body is a legitimate notification -- a subject-only ping. A null body
            // is a caller that forgot the field, which the NOT NULL column would reject anyway.
            assertThatNullPointerException().isThrownBy(() -> withBody(null));
            assertThat(withBody("").body()).isEmpty();
        }

        @Test
        @DisplayName("requires a channel and a priority")
        void requiresChannelAndPriority() {
            assertThatNullPointerException().isThrownBy(() -> new NotificationPayload(
                    RECIPIENT, null, SUBJECT, BODY, Priority.HIGH, null));
            assertThatNullPointerException().isThrownBy(() -> new NotificationPayload(
                    RECIPIENT, Channel.SERVICE, SUBJECT, BODY, null, null));
        }
    }

    @Nested
    @DisplayName("length limits mirroring the column widths")
    class LengthLimits {

        @Test
        @DisplayName("accepts a recipient exactly at the column width and rejects one over")
        void recipientBoundary() {
            assertThat(withRecipient("x".repeat(2048)).recipient()).hasSize(2048);
            assertThatIllegalArgumentException().isThrownBy(() -> withRecipient("x".repeat(2049)));
        }

        @Test
        @DisplayName("accepts a subject exactly at the column width and rejects one over")
        void subjectBoundary() {
            assertThat(withSubject("x".repeat(512)).subject()).hasSize(512);
            assertThatIllegalArgumentException().isThrownBy(() -> withSubject("x".repeat(513)));
        }

        @Test
        @DisplayName("measures characters, not UTF-16 units, as the column does")
        void countsCharactersAndNotCodeUnits() {
            // An emoji is one character for PostgreSQL and two for String.length(). Measured
            // in code units, a subject of 512 emoji would be rejected as if it were 1024 --
            // and the client would read an error naming a limit its subject does not exceed.
            String atTheLimit = "🚀".repeat(512);
            String overTheLimit = "🚀".repeat(513);

            assertThat(withSubject(atTheLimit).subject()).isEqualTo(atTheLimit);
            assertThatIllegalArgumentException().isThrownBy(() -> withSubject(overTheLimit));
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("defaults to an empty map when omitted")
        void defaultsToEmptyMap() {
            // Matches the DEFAULT '{}'::JSONB in the schema: absent metadata is an empty
            // object, never null, so no caller downstream has to null-check it.
            assertThat(withMetadata(null).metadata()).isEmpty();
        }

        @Test
        @DisplayName("is copied, so later changes to the caller's map do not leak in")
        void isDefensivelyCopied() {
            Map<String, String> mutable = new HashMap<>(Map.of("source", "ci"));
            NotificationPayload payload = withMetadata(mutable);

            mutable.put("injected", "after the fact");

            assertThat(payload.metadata()).containsOnlyKeys("source");
        }

        @Test
        @DisplayName("is unmodifiable, so it cannot be changed through the accessor")
        void isUnmodifiable() {
            Map<String, String> metadata = valid().metadata();

            assertThatThrownBy(() -> metadata.put("injected", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rejects a null key or value")
        void rejectsNullEntries() {
            Map<String, String> withNullValue = new HashMap<>();
            withNullValue.put("source", null);

            assertThatNullPointerException().isThrownBy(() -> withMetadata(withNullValue));
        }
    }
}
