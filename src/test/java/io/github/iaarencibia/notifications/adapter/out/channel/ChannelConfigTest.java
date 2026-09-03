package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The client the SERVICE channel actually posts with.
 *
 * <p>Asserted through the bean rather than through the factory helper the channel's own test
 * uses. Those two are different things, and only one of them ships: dropping the request factory
 * from the bean would leave a client with no timeouts at all -- the failure every guard around
 * those two values exists to prevent -- while every other test stayed green.
 */
class ChannelConfigTest {

    @Test
    @DisplayName("builds the outbound client with the configured timeouts, not with none")
    void theOutboundClientCarriesItsTimeouts() {
        RestClient client = new ChannelConfig().serviceChannelRestClient(RestClient.builder(), propertiesWith(
                Duration.ofMillis(40), Duration.ofMillis(40)));

        // A reserved, unroutable address (RFC 5737) on a port nothing serves.
        Instant started = Instant.now();
        assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() ->
                client.post().uri("http://192.0.2.1:9/never-answers").retrieve().toBodilessEntity());

        // How long it took is the assertion, not that it failed. Without a request factory the
        // call fails too -- the operating system gives up on its own, measured at forty-nine
        // seconds -- so a test that only checked the exception passed with the timeouts removed.
        assertThat(Duration.between(started, Instant.now()))
                .as("the configured timeout is what must end this call, not the operating system")
                .isLessThan(Duration.ofSeconds(5));
    }

    private static NotificationsProperties propertiesWith(Duration connect, Duration read) {
        return new NotificationsProperties("test-instance",
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Retry(3),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(connect, read)));
    }
}
