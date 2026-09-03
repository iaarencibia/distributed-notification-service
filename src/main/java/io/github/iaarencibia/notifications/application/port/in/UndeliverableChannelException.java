package io.github.iaarencibia.notifications.application.port.in;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.domain.Channel;

/**
 * A channel the domain defines and this deployment cannot deliver.
 *
 * <p>Refusing at intake rather than at dispatch is the point: accepting a notification nobody can
 * send would answer the caller {@code 202} and then leave a row that fails on every attempt until
 * its budget runs out. The caller would have been told its delivery was accepted, and it never
 * was.
 *
 * <p>It carries the same {@link DeliverableChannels} the decision was made with, so that whatever
 * explains the refusal is reading the object that caused it rather than a copy of the list.
 */
public class UndeliverableChannelException extends RuntimeException {

    private final Channel channel;
    private final DeliverableChannels deliverable;

    /**
     * @param channel     what the caller asked for
     * @param deliverable what this deployment can actually send
     */
    public UndeliverableChannelException(Channel channel, DeliverableChannels deliverable) {
        super("channel " + channel + " cannot be delivered by this service");
        this.channel = channel;
        this.deliverable = deliverable;
    }

    /**
     * @return the channel that was refused
     */
    public Channel channel() {
        return channel;
    }

    /**
     * @return what the caller may ask for instead
     */
    public DeliverableChannels deliverable() {
        return deliverable;
    }
}
