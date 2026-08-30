package io.github.iaarencibia.notifications.domain;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle graph is declared here independently of the implementation, so that a change
 * to the enum cannot quietly redefine what a legal transition is: the test would have to be
 * edited too, deliberately.
 */
class NotificationStatusTest {

    /** The expected graph, declared independently of the enum under test. */
    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = Map.of(
            NotificationStatus.PENDING, Set.of(NotificationStatus.DISPATCHING),
            NotificationStatus.DISPATCHING, Set.of(
                    NotificationStatus.SENT,
                    NotificationStatus.FAILED,
                    NotificationStatus.PENDING),
            NotificationStatus.SENT, Set.of(),
            NotificationStatus.FAILED, Set.of());

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    @DisplayName("allows exactly the declared transitions and nothing else")
    void allowsExactlyTheDeclaredTransitions(NotificationStatus source) {
        Set<NotificationStatus> allowed = ALLOWED.get(source);

        for (NotificationStatus target : NotificationStatus.values()) {
            assertThat(source.canTransitionTo(target))
                    .as("%s -> %s", source, target)
                    .isEqualTo(allowed.contains(target));
        }
    }

    @Test
    @DisplayName("a notification cannot reach SENT without passing through DISPATCHING")
    void cannotSkipDispatching() {
        assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.SENT)).isFalse();
        assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.FAILED)).isFalse();
    }

    @Test
    @DisplayName("SENT and FAILED are terminal; PENDING and DISPATCHING are not")
    void terminalStates() {
        assertThat(NotificationStatus.SENT.isTerminal()).isTrue();
        assertThat(NotificationStatus.FAILED.isTerminal()).isTrue();
        assertThat(NotificationStatus.PENDING.isTerminal()).isFalse();
        assertThat(NotificationStatus.DISPATCHING.isTerminal()).isFalse();
    }
}
