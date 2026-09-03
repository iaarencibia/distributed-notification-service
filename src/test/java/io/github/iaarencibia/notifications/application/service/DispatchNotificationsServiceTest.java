package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one pass of the dispatcher does with what it claims.
 *
 * <p>The deliveries run on the calling thread here, not because the real one does, but because a
 * pool would make every assertion below a race. What the pool buys is tested where it exists: in
 * the wiring, and in the integration test that drives real deliveries.
 */
class DispatchNotificationsServiceTest {

    private static final String INSTANCE = "notification-service-1";
    private static final Instant STARTED_AT = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-09-03T10:00:02Z");

    private final RecordingQueue queue = new RecordingQueue();
    private final FixedClock clock = new FixedClock(STARTED_AT, FINISHED_AT);

    @Test
    @DisplayName("claims a batch under this instance and reports how many it took")
    void claimsUnderThisInstance() {
        queue.willClaim(claimed(Channel.LOG, 0));

        int dispatched = serviceDelivering(new DispatchOutcome.Success(null)).dispatchDue();

        assertThat(dispatched).isEqualTo(1);
        assertThat(queue.claimedBatchSize).isEqualTo(25);
        assertThat(queue.claimedBy)
                .as("the reaper recovers work by this value; an anonymous claim is untraceable")
                .isEqualTo(INSTANCE);
    }

    @Test
    @DisplayName("times every attempt by the shared clock, never by this process")
    void timesTheAttemptByTheSharedClock() {
        // The instant a delivery finishes is what the next attempt is scheduled from, so a clock
        // that drifts from the one the claim query reads moves rows in and out of being due.
        queue.willClaim(claimed(Channel.SERVICE, 0));

        serviceDelivering(new DispatchOutcome.Success(200)).dispatchDue();

        NotificationAttempt attempt = queue.recorded().getFirst().attempt();
        assertThat(attempt.startedAt()).isEqualTo(STARTED_AT);
        assertThat(attempt.finishedAt()).isEqualTo(FINISHED_AT);
        assertThat(attempt.durationMs()).isEqualTo(2000);
    }

    @Test
    @DisplayName("writes the result against the version the notification was claimed at")
    void writesAgainstTheClaimedVersion() {
        // Not the version the row carries now. Against the current value the guard would pass
        // every time, and an instance returning from a long delivery would overwrite whoever
        // took its work after the reaper released it.
        queue.willClaim(new ClaimedNotification(dispatching(Channel.LOG), 7L));

        serviceDelivering(new DispatchOutcome.Success(null)).dispatchDue();

        assertThat(queue.recorded().getFirst().claimed().version()).isEqualTo(7L);
    }

    @Nested
    @DisplayName("what each outcome leaves behind")
    class Outcomes {

        @Test
        @DisplayName("a delivered notification is SENT, with no next attempt scheduled")
        void success() {
            queue.willClaim(claimed(Channel.SERVICE, 0));

            serviceDelivering(new DispatchOutcome.Success(202)).dispatchDue();

            Recorded recorded = queue.recorded().getFirst();
            assertThat(recorded.claimed().notification().status()).isEqualTo(NotificationStatus.SENT);
            assertThat(recorded.claimed().notification().sentAt()).isEqualTo(FINISHED_AT);
            assertThat(recorded.attempt().outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        }

        @Test
        @DisplayName("a retryable failure goes back to PENDING, due after the policy's delay")
        void retryable() {
            queue.willClaim(claimed(Channel.SERVICE, 0));

            serviceDelivering(new DispatchOutcome.RetryableFailure(503, "unavailable", null))
                    .dispatchDue();

            Notification notification = queue.recorded().getFirst().claimed().notification();
            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            // Counted from when the attempt ended, not when it began: a destination that took two
            // seconds to refuse has already had those two seconds.
            assertThat(notification.nextAttemptAt())
                    .isEqualTo(FINISHED_AT.plus(Duration.ofSeconds(5)));
            assertThat(notification.claimedBy())
                    .as("a rescheduled notification is nobody's, or no one else could claim it")
                    .isNull();
        }

        @Test
        @DisplayName("a permanent failure is FAILED at once, with attempts still on the budget")
        void permanent() {
            queue.willClaim(claimed(Channel.SERVICE, 0));

            serviceDelivering(new DispatchOutcome.PermanentFailure(400, "malformed"))
                    .dispatchDue();

            Notification notification = queue.recorded().getFirst().claimed().notification();
            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts())
                    .as("one attempt spent out of three: the budget is not what ended this")
                    .isEqualTo(1);
            assertThat(notification.lastError()).isEqualTo("malformed");
        }

        @Test
        @DisplayName("a retryable failure on the last attempt is FAILED, not rescheduled")
        void retryableOnTheLastAttempt() {
            // The boundary the budget exists for. Rescheduled here, the notification would be
            // claimed again and the aggregate would refuse to record a fourth attempt.
            queue.willClaim(claimed(Channel.SERVICE, 2));

            serviceDelivering(new DispatchOutcome.RetryableFailure(503, "still down", null))
                    .dispatchDue();

            Notification notification = queue.recorded().getFirst().claimed().notification();
            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("records a permanent failure for a channel this deployment cannot deliver")
    void aChannelWithNoAdapter() {
        // Reachable when a row is accepted by a deployment that had the adapter and claimed by
        // one that does not. Left alone it would be claimed, dropped, reaped and claimed again,
        // for ever, with nothing in the table saying why.
        queue.willClaim(claimed(Channel.EMAIL, 0));

        int dispatched = serviceDelivering(new DispatchOutcome.Success(null)).dispatchDue();

        assertThat(dispatched).isEqualTo(1);
        Notification notification = queue.recorded().getFirst().claimed().notification();
        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.lastError()).contains("EMAIL");
    }

    @Test
    @DisplayName("a lost claim does not stop the rest of the batch")
    void aLostClaimIsAbsorbed() {
        // Expected, not exceptional: the reaper released this row and somebody else delivered it.
        // Escaping here it would reach the pool's default handler, and the notifications queued
        // behind it in the same pass would never be attempted.
        queue.willClaim(claimed(Channel.LOG, 0), claimed(Channel.LOG, 0));
        queue.failFirstRecordWithLostClaim();

        int dispatched = serviceDelivering(new DispatchOutcome.Success(null)).dispatchDue();

        assertThat(dispatched).isEqualTo(2);
        assertThat(queue.recorded())
                .as("the second delivery is still recorded")
                .hasSize(1);
    }

    @Test
    @DisplayName("sends each notification through the adapter for its own channel")
    void routesByChannel() {
        RecordingSender log = new RecordingSender(Channel.LOG, new DispatchOutcome.Success(null));
        RecordingSender service =
                new RecordingSender(Channel.SERVICE, new DispatchOutcome.Success(200));
        queue.willClaim(claimed(Channel.SERVICE, 0));

        new DispatchNotificationsService(queue,
                Map.of(Channel.LOG, log, Channel.SERVICE, service), clock, policy(),
                Runnable::run, INSTANCE, 25).dispatchDue();

        assertThat(service.sent).hasSize(1);
        assertThat(log.sent)
                .as("the adapter for another channel is never asked")
                .isEmpty();
    }

    private DispatchNotificationsService serviceDelivering(DispatchOutcome outcome) {
        return new DispatchNotificationsService(queue,
                Map.of(Channel.LOG, new RecordingSender(Channel.LOG, outcome),
                        Channel.SERVICE, new RecordingSender(Channel.SERVICE, outcome)),
                clock, policy(), Runnable::run, INSTANCE, 25);
    }

    /** Jitter at zero so the scheduled delay is exact rather than a range. */
    private static RetryPolicy policy() {
        return new RetryPolicy(Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.0, () -> 0.0);
    }

    private static ClaimedNotification claimed(Channel channel, int attemptsAlreadySpent) {
        return new ClaimedNotification(dispatching(channel, attemptsAlreadySpent), 1L);
    }

    private static Notification dispatching(Channel channel) {
        return dispatching(channel, 0);
    }

    /**
     * @param channel             what the notification asks to be sent over
     * @param attemptsAlreadySpent how much of the budget of three is gone
     * @return a notification in the state the claim query leaves it in
     */
    private static Notification dispatching(Channel channel, int attemptsAlreadySpent) {
        NotificationPayload payload = new NotificationPayload("someone@example.test", channel,
                "a subject", "a body", Priority.MEDIUM, Map.of());

        return Notification.rehydrate(UUID.randomUUID(), payload, NotificationStatus.DISPATCHING,
                attemptsAlreadySpent, 3, STARTED_AT, null, STARTED_AT, INSTANCE,
                new CorrelationId("order-4471"), null, STARTED_AT, STARTED_AT, null);
    }

    /** What the queue was asked to write, so the assertions can look at it. */
    private record Recorded(ClaimedNotification claimed, NotificationAttempt attempt) {
    }

    /**
     * Stands in for storage. It hands out a prepared batch and keeps what it is told to write; it
     * is not a stub of the real behaviour and makes no attempt to be one.
     */
    private static final class RecordingQueue implements NotificationDispatchQueue {

        private final Deque<ClaimedNotification> toClaim = new ArrayDeque<>();
        private final List<Recorded> recorded = new ArrayList<>();
        private boolean failFirstRecord;
        private int claimedBatchSize;
        private String claimedBy;

        void willClaim(ClaimedNotification... notifications) {
            toClaim.addAll(List.of(notifications));
        }

        void failFirstRecordWithLostClaim() {
            this.failFirstRecord = true;
        }

        List<Recorded> recorded() {
            return recorded;
        }

        @Override
        public List<ClaimedNotification> claimDue(int batchSize, String claimedBy) {
            this.claimedBatchSize = batchSize;
            this.claimedBy = claimedBy;
            List<ClaimedNotification> claimed = List.copyOf(toClaim);
            toClaim.clear();
            return claimed;
        }

        @Override
        public void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt) {
            if (failFirstRecord) {
                failFirstRecord = false;
                throw new ClaimLostException(claimed.notification().id());
            }
            recorded.add(new Recorded(claimed, attempt));
        }
    }

    /** A channel that reports what the test told it to, and remembers what it was given. */
    private static final class RecordingSender implements NotificationChannelSender {

        private final Channel channel;
        private final DispatchOutcome outcome;
        private final List<Notification> sent = new ArrayList<>();

        RecordingSender(Channel channel, DispatchOutcome outcome) {
            this.channel = channel;
            this.outcome = outcome;
        }

        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public DispatchOutcome send(Notification notification) {
            sent.add(notification);
            return outcome;
        }
    }

    /** Two instants, in order, so a delivery has a measurable duration without sleeping. */
    private static final class FixedClock implements SystemClock {

        private final Deque<Instant> instants = new ArrayDeque<>();
        private Instant last;

        FixedClock(Instant... instants) {
            this.instants.addAll(List.of(instants));
            this.last = instants[instants.length - 1];
        }

        @Override
        public Instant now() {
            if (instants.isEmpty()) {
                return last;
            }
            return instants.removeFirst();
        }
    }
}
