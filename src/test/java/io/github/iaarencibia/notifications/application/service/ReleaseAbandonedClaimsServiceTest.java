package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The reaper's one decision: how long a claim may stand before its owner is presumed dead.
 *
 * <p>It looks like a pass-through, and the threshold is why it is not. That number is the line
 * between recovering the work of an instance that died and taking work away from one that is
 * merely slow -- and the second produces duplicate delivery deliberately. Nothing else in the
 * system is positioned to notice if the wrong duration were handed to it.
 */
class ReleaseAbandonedClaimsServiceTest {

    private final RecordingClaims claims = new RecordingClaims();

    @Test
    @DisplayName("releases against the threshold it was built with, and not some other duration")
    void passesTheConfiguredThreshold() {
        // That the right duration reaches this constructor is a different question, decided by
        // the wiring and covered by DispatchWiringTest. This one only says the service passes on
        // what it was given.
        new ReleaseAbandonedClaimsService(claims, Duration.ofMinutes(5)).releaseAbandonedClaims();

        assertThat(claims.releasedOlderThan).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("reports how many claims were released")
    void reportsWhatWasReleased() {
        claims.willRelease(3);

        int released = new ReleaseAbandonedClaimsService(claims, Duration.ofMinutes(5))
                .releaseAbandonedClaims();

        assertThat(released).isEqualTo(3);
    }

    @Test
    @DisplayName("refuses a threshold of zero, which would release every claim as it is taken")
    void refusesANonPositiveThreshold() {
        // Zero means every claim is abandoned the instant it exists: rows would be returned to
        // PENDING while the instance that owns them is mid-delivery, and every notification would
        // be sent as many times as it is claimed.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, Duration.ZERO))
                .withMessageContaining("staleClaimTimeout");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, Duration.ofSeconds(-1)))
                .withMessageContaining("staleClaimTimeout");
    }

    @Test
    @DisplayName("refuses to be built without a threshold at all")
    void refusesAMissingThreshold() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, null));
    }

    /** Records the threshold it was asked to release against. */
    private static final class RecordingClaims implements AbandonedClaims {

        private Duration releasedOlderThan;
        private int released;

        void willRelease(int count) {
            this.released = count;
        }

        @Override
        public int release(Duration staleAfter) {
            this.releasedOlderThan = staleAfter;
            return released;
        }
    }
}
