package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;

/**
 * One way of delivering a notification, said in the language of the domain.
 *
 * <p>An implementation answers for exactly one {@link Channel} and reports what happened as a
 * {@link DispatchOutcome}. Nothing above this port knows what a status code or an SMTP session
 * is: the decision the dispatcher has to make is whether to retry, and that decision is already
 * made by the time the outcome crosses back.
 *
 * <p>Which channels the service can actually deliver is the set of implementations wired at
 * startup, and nothing else. That is what lets a channel become supported by adding a bean --
 * the intake reads the same set to decide what it accepts, so the two cannot drift apart.
 *
 * <p>Implementations must not throw for a failed delivery. A destination that refuses, or that
 * never answers, is an outcome and not an error: throwing would put the dispatcher in the
 * business of interpreting exceptions from every channel it ever gains.
 */
public interface NotificationChannelSender {

    /**
     * @return the channel this sender answers for
     */
    Channel channel();

    /**
     * Attempts one delivery. Never retries on its own: how many attempts a notification gets,
     * and how long they are spaced, belongs to the caller and to the retry policy.
     *
     * @param notification what to deliver, carrying both its payload and its identity
     * @return what happened, classified as delivered, worth retrying, or permanently rejected
     */
    DispatchOutcome send(Notification notification);
}
