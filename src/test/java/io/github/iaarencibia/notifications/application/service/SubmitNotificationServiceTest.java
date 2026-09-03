package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.UndeliverableChannelException;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The intake use case, with storage replaced by a double that enforces the one rule the real
 * adapter delegates to a unique index.
 *
 * <p>The double is written out rather than mocked because the behaviour under test is a
 * conversation, not a call: save, collide, look up, answer. A mock would have to be told the
 * answer to the lookup, which is the part worth proving.
 */
class SubmitNotificationServiceTest {

    private static final CorrelationId CORRELATION_ID = new CorrelationId("order-4471");
    private static final int MAX_ATTEMPTS = 3;

    /** What the deployment under test can deliver. The payload below uses SERVICE. */
    private static final DeliverableChannels DELIVERABLE =
            new DeliverableChannels(Set.of(Channel.LOG, Channel.SERVICE));

    private final InMemoryNotifications repository = new InMemoryNotifications();
    private final SubmitNotificationService service =
            new SubmitNotificationService(repository, MAX_ATTEMPTS, DELIVERABLE);

    @Test
    @DisplayName("refuses a channel nothing can deliver, and writes no row")
    void refusesAnUndeliverableChannel() {
        // EMAIL is a legal value of the domain type and has no adapter in this deployment.
        // Accepting it would answer the caller 202 and leave a row that fails every attempt
        // until its budget ran out -- a delivery reported as accepted that was never possible.
        NotificationPayload toEmail = new NotificationPayload("someone@example.test",
                Channel.EMAIL, "Your order shipped", "It is on its way.", Priority.HIGH, Map.of());

        assertThatExceptionOfType(UndeliverableChannelException.class)
                .isThrownBy(() -> service.submit(
                        new SubmitNotificationCommand(toEmail, CORRELATION_ID, "key-email")))
                .satisfies(thrown -> {
                    assertThat(thrown.channel()).isEqualTo(Channel.EMAIL);
                    // Sorted and named as they travel, so the caller is told what to use instead.
                    assertThat(thrown.deliverable().describe()).isEqualTo("LOG, SERVICE");
                });

        assertThat(repository.stored())
                .as("nothing may be stored for a notification that cannot be sent")
                .isEmpty();
    }

    @Test
    @DisplayName("stores the notification and answers with its identifier")
    void storesAndAnswersWithTheIdentifier() {
        UUID id = service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        assertThat(repository.stored()).hasSize(1);
        assertThat(repository.only().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("hands the domain exactly what the caller asked for")
    void passesTheCallerIntentThrough() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        Notification stored = repository.only();
        assertThat(stored.payload()).isEqualTo(payload());
        assertThat(stored.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(stored.idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("creates it PENDING and claimable, so the dispatcher needs no second signal")
    void createsItQueued() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        Notification stored = repository.only();
        assertThat(stored.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(stored.createdAt());
    }

    @Test
    @DisplayName("stamps the configured budget on the row, not a number of its own")
    void stampsTheConfiguredBudget() {
        // Persisted per row on purpose: a notification keeps the budget it was promised even if
        // the configuration changes while it is still in flight.
        SubmitNotificationService withADifferentBudget =
                new SubmitNotificationService(repository, 7, DELIVERABLE);

        withADifferentBudget.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        assertThat(repository.only().maxAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("accepts a notification carrying no idempotency key")
    void acceptsAMissingKey() {
        UUID id = service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, null));

        assertThat(id).isNotNull();
        assertThat(repository.only().idempotencyKey()).isNull();
    }

    @Test
    @DisplayName("answers a repeated key with the identifier the first request created")
    void answersARepeatedKeyWithTheOriginalIdentifier() {
        UUID first = service.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        UUID second = service.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("stores nothing the second time, which is the point of the whole mechanism")
    void storesNothingOnARepeatedKey() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        assertThat(repository.stored()).hasSize(1);
    }

    @Test
    @DisplayName("lets two notifications of one operation through, each with its own key")
    void acceptsTwoNotificationsOfTheSameOperation() {
        // The identifiers pull in opposite directions and must not be confused: a correlation id
        // is shared by every notification of an operation, an idempotency key never is. Were the
        // service to treat the correlation id as the key, only the first would ever be sent.
        UUID toTheBuyer = service.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-to-the-buyer"));
        UUID toTheWarehouse = service.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-to-the-warehouse"));

        assertThat(toTheBuyer).isNotEqualTo(toTheWarehouse);
        assertThat(repository.stored()).hasSize(2);
    }

    @Test
    @DisplayName("refuses to answer when the notification that rejected the insert cannot be read")
    void refusesToInventAnAnswer() {
        // Nothing in this service deletes a row, so a key that is taken and yet unreadable is
        // corruption. Answering with a fresh identifier would tell the caller its notification
        // was accepted when none exists.
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));
        repository.hideEverything();

        assertThatExceptionOfType(IdempotencyKeyAlreadyUsedException.class).isThrownBy(
                () -> service.submit(
                        new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated")));
    }

    @Test
    @DisplayName("requires a command, and says so rather than failing wherever it first reads one")
    void requiresACommand() {
        // The message is asserted, and that is the whole point of the test. Without the guard the
        // call still throws a NullPointerException -- the very next statement dereferences the
        // argument -- so a bare assertThatNullPointerException would pass with the guard deleted
        // and prove nothing. What the guard buys is a caller told which argument was null instead
        // of being handed a stack trace pointing into the middle of the method.
        assertThatNullPointerException().isThrownBy(() -> service.submit(null))
                .withMessage("command must not be null");
    }

    private static NotificationPayload payload() {
        return new NotificationPayload("https://destination.test/hook", Channel.SERVICE,
                "Your order shipped", "It is on its way.", Priority.HIGH,
                Map.of("orderId", "4471"));
    }

    /**
     * Storage reduced to the one rule the real adapter does not implement either: the database
     * refuses a repeated idempotency key, and everything above finds out by being told.
     */
    private static final class InMemoryNotifications implements NotificationRepository {

        private final Map<UUID, Notification> byId = new LinkedHashMap<>();
        private final Map<String, Notification> byKey = new HashMap<>();
        private boolean hidden;

        @Override
        public void save(Notification notification) {
            String key = notification.idempotencyKey();
            if (key != null && byKey.containsKey(key)) {
                throw new IdempotencyKeyAlreadyUsedException(key,
                        new IllegalStateException("unique index"));
            }
            byId.put(notification.id(), notification);
            if (key != null) {
                byKey.put(key, notification);
            }
        }

        @Override
        public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            return hidden ? Optional.empty() : Optional.ofNullable(byKey.get(idempotencyKey));
        }

        /** Leaves the keys taken but the rows unreadable, which is what corruption looks like. */
        void hideEverything() {
            this.hidden = true;
        }

        Map<UUID, Notification> stored() {
            return byId;
        }

        Notification only() {
            assertThat(byId).hasSize(1);
            return byId.values().iterator().next();
        }
    }
}
