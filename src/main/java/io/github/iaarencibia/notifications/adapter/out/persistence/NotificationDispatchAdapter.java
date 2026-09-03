package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * The outbound side of dispatch: claiming work, reporting on it, and recovering what was
 * abandoned.
 *
 * <p>It sits outside {@link NotificationJpaStore} for the same reason its sibling does. The
 * provider's stale-state failure becomes Spring's {@code OptimisticLockingFailureException} in the
 * interceptor around a {@code @Repository} bean, on the way out of it; a {@code catch} written
 * inside that bean would never run.
 *
 * <p>It answers two ports rather than one because both are statements against the same table and
 * a second class would add nothing. The separation that matters is on the calling side: the reaper
 * holds {@link AbandonedClaims} and has no way to claim anything.
 */
@Component
class NotificationDispatchAdapter implements NotificationDispatchQueue, AbandonedClaims {

    private final NotificationJpaStore store;

    NotificationDispatchAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public List<ClaimedNotification> claimDue(int batchSize, String claimedBy) {
        return store.claimDue(batchSize, claimedBy).stream()
                .map(entity -> new ClaimedNotification(NotificationMapper.toDomain(entity),
                        entity.getVersion()))
                .toList();
    }

    @Override
    public void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt) {
        NotificationEntity entity = NotificationMapper.toEntity(claimed.notification());
        // The version the row carried when it was claimed, not the one it carries now. Written
        // against the current value the guard would pass every time and protect nothing.
        entity.setVersion(claimed.version());

        try {
            store.recordAttempt(entity, NotificationMapper.toEntity(attempt));
        } catch (OptimisticLockingFailureException lostRace) {
            throw new ClaimLostException(claimed.notification().id());
        }
    }

    @Override
    public int release(Duration staleAfter) {
        return store.releaseStaleClaims(staleAfter);
    }
}
