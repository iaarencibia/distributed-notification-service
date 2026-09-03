package io.github.iaarencibia.notifications.application.port.in;

/**
 * The recovery pass: give back the work of instances that never came back.
 *
 * <p>It exists because {@code DISPATCHING} is a lock held outside any transaction. That is what
 * lets the dispatcher release its database connection before making a network call, and the price
 * of it is that an instance which dies mid-delivery leaves a row nobody owns and nobody will
 * touch again.
 */
public interface ReleaseAbandonedClaimsUseCase {

    /**
     * Returns to PENDING every claim older than the configured threshold.
     *
     * @return how many claims were released, which is zero on a healthy system
     */
    int releaseAbandonedClaims();
}
