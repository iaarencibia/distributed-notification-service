package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Test
    @DisplayName("wires the SERVICE channel with that client when the configuration runs")
    void theChannelIsWiredWithIt() {
        // The test above builds the bean by calling the method, which cannot notice a client the
        // container declines to hand over. This one goes through the container.
        channelContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationChannelSender.class);
        });
    }

    @Test
    @DisplayName("refuses to hand that client to an injection point that asks by type alone")
    void theOutboundClientIsNotOfferedByTypeAlone() {
        // Its timeouts are chosen for a destination this service does not control. A later call
        // to some other service, asking for a RestClient by type, would inherit a five-second
        // read timeout nobody chose for it -- and would look correct while doing so.
        channelContext().withUserConfiguration(AsksForAnyRestClient.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("RestClient");
                });
    }

    /**
     * @return the channel configuration with the two beans Boot would otherwise supply it
     */
    private static ApplicationContextRunner channelContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ChannelConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(NotificationsProperties.class,
                        () -> propertiesWith(Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    /**
     * Stands in for a future collaborator that wants an HTTP client and has no idea this one
     * exists. Its parameter is named so that resolution by name cannot rescue it either.
     */
    @Configuration(proxyBeanMethods = false)
    static class AsksForAnyRestClient {

        @Bean
        String somethingThatCallsAnotherService(RestClient anyRestClient) {
            return anyRestClient.toString();
        }
    }

    /**
     * Only the two timeouts are what this test is about. The rest of the configuration has to be
     * present and valid for the record to exist at all, and carries the shipped defaults so that
     * nothing here reads as a value chosen for the test.
     *
     * @param connect the connect timeout under test
     * @param read    the read timeout under test
     * @return a complete configuration differing only in those two
     */
    private static NotificationsProperties propertiesWith(Duration connect, Duration read) {
        return new NotificationsProperties("test-instance",
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Dispatch(Duration.ofSeconds(1), 25, 8, 100),
                new NotificationsProperties.Reaper(Duration.ofMinutes(5), Duration.ofMinutes(1)),
                new NotificationsProperties.Retry(3, Duration.ofSeconds(5), 3.0,
                        Duration.ofMinutes(5), 0.2),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(connect, read)));
    }
}
