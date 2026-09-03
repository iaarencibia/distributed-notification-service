package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * One pass of the dispatcher.
 *
 * <p>The shape that matters is where the transactions end. Claiming a batch is one short
 * transaction; recording what a delivery came to is another; the network call happens between
 * them, holding no database connection at all. A single transaction spanning the call would tie up
 * one connection for as long as the slowest destination takes to time out, and a handful of those
 * would empty the pool the intake endpoint writes through -- a service that stops accepting
 * notifications because somebody else's server is slow.
 *
 * <p>What the gap costs is that a claim is a lock nothing enforces. An instance that dies holding
 * one leaves a row nobody will touch, which is why the reaper exists; and a claim the reaper
 * releases while its owner is still delivering is a notification that may be delivered twice.
 * At-least-once, declared rather than discovered.
 */
public final class DispatchNotificationsService implements DispatchDueNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationsService.class);

    private final NotificationDispatchQueue queue;
    private final Map<Channel, NotificationChannelSender> senders;
    private final SystemClock clock;
    private final RetryPolicy retryPolicy;
    private final Executor deliveries;
    private final String instanceId;
    private final int batchSize;

    /**
     * @param queue       where work is claimed from and results are reported to
     * @param senders     the adapter answering for each deliverable channel
     * @param clock       the one clock; every instant handed to the aggregate comes from here
     * @param retryPolicy the schedule a rescheduled notification follows
     * @param deliveries  runs the deliveries. Bounded, so that a slow destination costs a fixed
     *                    number of threads instead of one per notification
     * @param instanceId  identifies this instance on every row it claims
     * @param batchSize   how many notifications one pass takes
     */
    public DispatchNotificationsService(NotificationDispatchQueue queue,
            Map<Channel, NotificationChannelSender> senders, SystemClock clock,
            RetryPolicy retryPolicy, Executor deliveries, String instanceId, int batchSize) {

        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.senders = Map.copyOf(Objects.requireNonNull(senders, "senders must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int dispatchDue() {
        List<ClaimedNotification> claimed = queue.claimDue(batchSize, instanceId);
        for (ClaimedNotification each : claimed) {
            deliveries.execute(() -> deliver(each));
        }
        return claimed.size();
    }

    /**
     * One delivery, from the claim to the record of what it came to.
     *
     * <p>Nothing may escape here. This runs on a worker of a pool whose only other option is to
     * hand the throwable to a default handler that prints it and moves on, leaving the row
     * DISPATCHING with nothing said about why.
     *
     * @param claimed the notification this instance owns, and the version it owns it at
     */
    private void deliver(ClaimedNotification claimed) {
        Notification notification = claimed.notification();
        try {
            Instant startedAt = clock.now();
            DispatchOutcome outcome = attemptDelivery(notification);
            Instant finishedAt = clock.now();

            NotificationAttempt attempt =
                    notification.recordAttempt(outcome, startedAt, finishedAt, retryPolicy);
            queue.recordAttempt(new ClaimedNotification(notification, claimed.version()), attempt);

            log.debug("dispatched notification {} over {}: {}", notification.id(),
                    notification.payload().channel(), attempt.outcome());
        } catch (ClaimLostException lostRace) {
            // Expected, not a defect: the reaper released this claim, so the state this result
            // was computed from is stale and the write was refused. Whether the notification has
            // since been delivered by somebody else is not something this instance can tell.
            log.info("discarded the result of notification {}: {}", notification.id(),
                    lostRace.getMessage());
        } catch (RuntimeException unexpected) {
            // Covers the whole delivery, not only the write: the clock, the channel and the
            // record. The row stays claimed either way, so the line has to say what an operator
            // needs to know -- that it will not move again until the reaper releases it.
            log.error("failed to dispatch notification {}; it stays claimed until the reaper "
                    + "releases it", notification.id(), unexpected);
        }
    }

    /**
     * @param notification the notification to deliver
     * @return what the channel reported, or a permanent failure when no channel answers for it
     */
    private DispatchOutcome attemptDelivery(Notification notification) {
        Channel channel = notification.payload().channel();
        NotificationChannelSender sender = senders.get(channel);

        if (sender == null) {
            // Only reachable for a row accepted by a deployment that could deliver this channel
            // and claimed by one that cannot. Failed permanently rather than left alone: retrying
            // would not find an adapter, and a row put back would be claimed and dropped forever.
            return new DispatchOutcome.PermanentFailure(null,
                    "this deployment has no adapter for channel " + channel);
        }
        return sender.send(notification);
    }
}
