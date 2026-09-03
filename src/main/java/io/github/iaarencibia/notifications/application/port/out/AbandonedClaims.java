package io.github.iaarencibia.notifications.application.port.out;

import java.time.Duration;

/**
 * Work whose owner never came back. A port of its own so that the reaper, which performs
 * maintenance rather than delivery, has no way to claim anything.
 */
public interface AbandonedClaims {

    /**
     * Returns to PENDING every notification claimed longer ago than {@code staleAfter}, measured
     * against the database clock -- the comparison being between claim timestamps a different
     * instance wrote and the present, which is exactly where two clocks would disagree.
     *
     * @param staleAfter how long a claim may stand before its owner is assumed dead
     * @return how many claims were released
     */
    int release(Duration staleAfter);
}
