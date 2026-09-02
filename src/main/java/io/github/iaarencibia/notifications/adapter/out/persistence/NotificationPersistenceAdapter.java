package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.Notification;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * The outbound side of persistence: the only class that knows both the aggregate and the table.
 *
 * <p>It sits outside {@link NotificationJpaStore} rather than doing the work itself, and the
 * reason is worth stating because it is easy to get wrong. Spring turns the persistence
 * provider's constraint violation into its own {@code DataIntegrityViolationException} in the
 * interceptor around a {@code @Repository} bean -- which is to say, on the way out of it. Code
 * written inside that bean cannot catch the translated exception, because the translation has not
 * happened yet. Reacting to it therefore has to live one call further out, which is here.
 *
 * <p>What that buys is a port whose contract is the application's own: the layer above hears
 * "that key is taken", not "an integrity constraint failed", and needs neither the framework nor
 * the provider on its classpath to understand the difference.
 */
@Component
class NotificationPersistenceAdapter implements NotificationRepository {

    private final NotificationJpaStore store;

    NotificationPersistenceAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public void save(Notification notification) {
        try {
            store.insert(NotificationMapper.toEntity(notification));
        } catch (DataIntegrityViolationException violation) {
            if (notification.idempotencyKey() == null) {
                // With no key the partial unique index does not cover this row at all, so
                // whatever was violated is not something the caller can recover from.
                throw violation;
            }
            // Narrowed by the key rather than by the constraint's name, which would be a literal
            // with nothing keeping it honest. It is enough here: every other constraint on the
            // table is satisfied by construction for a row submit() has just built -- PENDING,
            // no attempts spent, no claim, and enums the domain owns.
            throw new IdempotencyKeyAlreadyUsedException(notification.idempotencyKey(), violation);
        }
    }

    @Override
    public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
        // A null would compare as SQL null and quietly match nothing, reporting "no such
        // notification" for what is really a caller that forgot to pass the key.
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        return store.findByIdempotencyKey(idempotencyKey).map(NotificationMapper::toDomain);
    }
}
