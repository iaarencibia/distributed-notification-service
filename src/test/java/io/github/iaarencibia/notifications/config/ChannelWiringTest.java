package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * What the wiring makes of the channel adapters it finds.
 *
 * <p>What is deliverable is never written down; it is derived from the beans present. That is the
 * property worth protecting, because the alternative -- a list kept in step by hand -- is the one
 * thing this shape exists to avoid.
 */
class ChannelWiringTest {

    private final UseCaseConfig config = new UseCaseConfig();

    @Test
    @DisplayName("reads the deliverable channels off the adapters, not off a list")
    void derivesTheChannelsFromTheAdapters() {
        DeliverableChannels deliverable =
                config.deliverableChannels(List.of(senderFor(Channel.LOG), senderFor(Channel.SERVICE)));

        assertThat(deliverable.includes(Channel.LOG)).isTrue();
        assertThat(deliverable.includes(Channel.SERVICE)).isTrue();
        assertThat(deliverable.includes(Channel.EMAIL))
                .as("a channel with no adapter is not deliverable, whatever the model says")
                .isFalse();
    }

    @Test
    @DisplayName("a channel becomes deliverable by adding its adapter, and nothing else")
    void anAddedAdapterIsEnough() {
        // The whole promise of deriving the set: no validation and no error message has to be
        // found and updated for EMAIL to start being accepted.
        DeliverableChannels deliverable = config.deliverableChannels(
                List.of(senderFor(Channel.LOG), senderFor(Channel.SERVICE), senderFor(Channel.EMAIL)));

        assertThat(deliverable.includes(Channel.EMAIL)).isTrue();
        assertThat(deliverable.describe()).isEqualTo("LOG, SERVICE, EMAIL");
    }

    @Test
    @DisplayName("refuses to start when two adapters claim the same channel")
    void refusesTwoAdaptersForOneChannel() {
        // Without this, which of the two delivers would be settled by the order Spring returns
        // the beans in -- a behaviour nobody chose and no test could pin.
        assertThatIllegalStateException()
                .isThrownBy(() -> config.deliverableChannels(
                        List.of(senderFor(Channel.SERVICE), senderFor(Channel.SERVICE))))
                .withMessageContaining("SERVICE");
    }


    /** A sender that answers for one channel and would never be asked to deliver anything. */
    private static NotificationChannelSender senderFor(Channel channel) {
        return new NotificationChannelSender() {

            @Override
            public Channel channel() {
                return channel;
            }

            @Override
            public DispatchOutcome send(Notification notification) {
                throw new UnsupportedOperationException("not part of what this test asserts");
            }
        };
    }
}
