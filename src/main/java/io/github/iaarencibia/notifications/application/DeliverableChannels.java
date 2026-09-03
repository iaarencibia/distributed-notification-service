package io.github.iaarencibia.notifications.application;

import io.github.iaarencibia.notifications.domain.Channel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The channels this deployment can actually send.
 *
 * <p>{@link Channel} says which channels exist; this says which are switched on, and that depends
 * on the adapters wired into this process. A channel becomes deliverable the day its adapter is
 * added, with no list to bring up to date -- and a list that has to be kept in step eventually
 * is not.
 *
 * <p>A type rather than a bare set, because the use case asks it whether to accept and the web
 * adapter asks it what to tell a caller: one object, so the two cannot disagree.
 *
 * @param value the channels with an adapter behind them
 */
public record DeliverableChannels(Set<Channel> value) {

    public DeliverableChannels {
        // EnumSet.copyOf refuses an empty collection that is not already one, so the empty case
        // is handled before it.
        value = value.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(value));
    }

    /**
     * @param channel what a caller asked for
     * @return whether anything in this deployment can send it
     */
    public boolean includes(Channel channel) {
        return value.contains(channel);
    }

    /**
     * The set is never empty in a running deployment: the wiring asks Spring for the channel
     * adapters as a required dependency, so a process with none fails to start rather than
     * starting with nothing to deliver through.
     *
     * @return the channels named the way they are sent, in the model's own order so that two
     *         identical refusals read identically
     */
    public String describe() {
        return value.stream().map(Channel::name).collect(Collectors.joining(", "));
    }
}
