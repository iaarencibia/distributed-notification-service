package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.in.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;

import java.time.Duration;
import java.util.Objects;

/**
 * The reaper.
 *
 * <p>It holds one thing the scheduler that drives it has no business knowing: how long a claim may
 * stand before its owner is presumed dead. That is a policy of the application, not a detail of
 * how often a timer fires.
 */
public final class ReleaseAbandonedClaimsService implements ReleaseAbandonedClaimsUseCase {

    private final AbandonedClaims abandonedClaims;
    private final Duration staleClaimTimeout;

    /**
     * @param abandonedClaims   where claims are released
     * @param staleClaimTimeout how long a claim may stand. It cannot be shortened without
     *                          consequence: the threshold is what separates an instance that died
     *                          from one that is merely slow, and getting that wrong means
     *                          delivering the same notification twice on purpose
     */
    public ReleaseAbandonedClaimsService(AbandonedClaims abandonedClaims,
            Duration staleClaimTimeout) {
        this.abandonedClaims = Objects.requireNonNull(abandonedClaims,
                "abandonedClaims must not be null");
        Objects.requireNonNull(staleClaimTimeout, "staleClaimTimeout must not be null");
        if (staleClaimTimeout.isZero() || staleClaimTimeout.isNegative()) {
            throw new IllegalArgumentException("staleClaimTimeout must be positive");
        }
        this.staleClaimTimeout = staleClaimTimeout;
    }

    @Override
    public int releaseAbandonedClaims() {
        return abandonedClaims.release(staleClaimTimeout);
    }
}
