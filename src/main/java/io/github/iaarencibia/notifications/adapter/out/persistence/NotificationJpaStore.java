package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.domain.Notification;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The rows, and nothing else. Every statement this application issues against the notification
 * table starts here, and each call carries its own short transaction.
 *
 * <p>The transactions are per call rather than per use case on purpose. The intake has one write,
 * so there is nothing to hold together -- and the recovery from a taken idempotency key depends on
 * the failed insert being finished and rolled back before the lookup that follows it runs.
 */
@Repository
class NotificationJpaStore {

    /**
     * Ordered by the claimable index exactly, so the rows arrive sorted and no sort is needed.
     *
     * <p>{@code SKIP LOCKED} is what makes several instances useful rather than merely safe:
     * without it they would queue behind one another on the same rows and dispatch in turn. With
     * it, whoever arrives second passes over what is already taken and claims different work.
     */
    private static final String CLAIM_DUE = """
            SELECT * FROM notification
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY priority_rank, next_attempt_at, created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """;

    /**
     * One statement for the whole sweep, and the version bump is the part that matters: it is
     * what makes an instance that comes back from a long delivery lose to the row it no longer
     * owns, instead of writing over whoever took it next.
     */
    private static final String RELEASE_STALE_CLAIMS = """
            UPDATE notification
               SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL,
                   updated_at = now(), version = version + 1
             WHERE status = 'DISPATCHING'
               AND claimed_at < now() - make_interval(secs => :seconds)
            """;

    private final EntityManager entityManager;

    NotificationJpaStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    void insert(NotificationEntity entity) {
        entityManager.persist(entity);
        // Flushed on purpose rather than left to the commit. The unique index is what rejects a
        // repeated idempotency key, and the caller has to see that rejection while it can still
        // do something about it -- at commit time the decision has been made for it.
        entityManager.flush();
    }

    /**
     * Takes ownership of up to {@code batchSize} due notifications, and returns the rows as they
     * now stand.
     *
     * <p>The transition itself is the aggregate's: each row is rehydrated, asked to claim, and
     * written back. Doing it in the UPDATE would be one statement fewer and would leave the only
     * two lifecycle moves in this application that skip the domain instead of one.
     *
     * @param batchSize how many to take at most
     * @param claimedBy the instance taking them
     * @return the claimed rows, carrying the version each must later be written against
     */
    @Transactional
    List<NotificationEntity> claimDue(int batchSize, String claimedBy) {
        Instant now = databaseNow();
        List<NotificationEntity> due = dueFor(now, batchSize);

        for (NotificationEntity entity : due) {
            Notification notification = NotificationMapper.toDomain(entity);
            notification.claim(now, claimedBy);
            NotificationMapper.applyLifecycle(entity, notification);
        }

        // The rows are returned still managed. The commit that ends this transaction flushes the
        // claims, and the provider writes each new version onto the instance as it does, so the
        // caller reads the version its own claim produced.
        return due;
    }

    /**
     * Writes the result of one delivery: the attempt, and the state it left the notification in.
     *
     * @param claimed the notification as the dispatch left it, carrying the version it was
     *                claimed at
     * @param attempt the record of what happened
     */
    @Transactional
    void recordAttempt(NotificationEntity claimed, NotificationAttemptEntity attempt) {
        entityManager.merge(claimed);
        entityManager.persist(attempt);
        // Both statements are sent before this method returns, so a claim that was lost is
        // reported to the caller rather than surfacing later as a failed commit it cannot place.
        entityManager.flush();
    }

    /**
     * @param staleAfter how long a claim may stand before its owner is assumed dead
     * @return how many claims were released
     */
    @Transactional
    int releaseStaleClaims(Duration staleAfter) {
        return entityManager.createNativeQuery(RELEASE_STALE_CLAIMS)
                .setParameter("seconds", (double) staleAfter.toMillis() / 1000)
                .executeUpdate();
    }

    /**
     * The one clock.
     *
     * <p>Called from {@link #claimDue} it runs inside that transaction, where PostgreSQL holds
     * {@code now()} fixed, so the instant deciding what is due is the same one written onto every
     * row claimed. Called from outside it takes a transaction of its own, which is all a caller
     * asking the time needs.
     *
     * @return the database's current instant
     */
    @Transactional(readOnly = true)
    Instant databaseNow() {
        return (Instant) entityManager.createNativeQuery("SELECT now()", Instant.class)
                .getSingleResult();
    }

    /**
     * @param now       the instant a notification must already be due at
     * @param batchSize how many to lock at most
     * @return the rows this transaction now holds the lock on
     */
    @SuppressWarnings("unchecked")
    private List<NotificationEntity> dueFor(Instant now, int batchSize) {
        return entityManager.createNativeQuery(CLAIM_DUE, NotificationEntity.class)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }

    @Transactional(readOnly = true)
    Optional<NotificationEntity> findByIdempotencyKey(String idempotencyKey) {
        return entityManager
                .createQuery("SELECT n FROM NotificationEntity n WHERE n.idempotencyKey = :key",
                        NotificationEntity.class)
                .setParameter("key", idempotencyKey)
                .getResultStream()
                .findFirst();
    }
}
