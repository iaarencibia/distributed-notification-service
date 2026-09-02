package io.github.iaarencibia.notifications.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
