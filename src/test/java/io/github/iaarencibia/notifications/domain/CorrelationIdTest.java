package io.github.iaarencibia.notifications.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The value arrives from the client, is stored in an indexed column and is emitted on every
 * log line of every dispatch attempt. Those three destinations are what the validation here
 * protects: an unbounded value inflates the index, and an unrestricted one can forge log
 * entries.
 */
class CorrelationIdTest {

    @Test
    @DisplayName("accepts the identifiers a caller is likely to supply")
    void acceptsUsualIdentifiers() {
        assertThat(new CorrelationId("order-4471").value()).isEqualTo("order-4471");
        assertThat(new CorrelationId("4bf92f3577b34da6a3ce929d0e0e4736").value()).hasSize(32);
        assertThat(new CorrelationId("checkout:eu-west-1:8821").value()).contains(":");
    }

    @Test
    @DisplayName("requires a value that is neither null nor blank")
    void requiresAValue() {
        assertThatNullPointerException().isThrownBy(() -> new CorrelationId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId(""));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("   "));
    }

    @Test
    @DisplayName("accepts a value exactly at the column width and rejects one over")
    void lengthBoundary() {
        assertThat(new CorrelationId("x".repeat(128)).value()).hasSize(128);
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("x".repeat(129)));
    }

    @Test
    @DisplayName("rejects characters that could forge a log entry")
    void rejectsLogForgingCharacters() {
        // Every dispatch attempt writes this value into a log line. A newline in it would let
        // a caller append entries of its own invention to the log.
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("order\n4471"));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("order\r4471"));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("order 4471"));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("{\"level\":\"ERROR\"}"));
    }

    @Test
    @DisplayName("generates a valid identifier for callers that supply none")
    void generatesWhenAbsent() {
        CorrelationId generated = CorrelationId.generate();

        assertThat(generated.value()).hasSize(36);
        assertThat(CorrelationId.generate()).isNotEqualTo(generated);
    }

    @Test
    @DisplayName("prints as the bare value, so log lines carry no wrapper noise")
    void printsAsTheBareValue() {
        assertThat(new CorrelationId("order-4471")).hasToString("order-4471");
    }
}
