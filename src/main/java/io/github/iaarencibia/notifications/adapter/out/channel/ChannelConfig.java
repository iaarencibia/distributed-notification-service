package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Assembles the channels that need something built for them.
 *
 * <p>It lives beside the adapters rather than in the configuration package because what it wires
 * is an adapter's own machinery -- a client with its timeouts -- and nothing above this package
 * has any business knowing that the SERVICE channel speaks HTTP at all. The log channel needs
 * none of this and declares itself.
 */
@Configuration(proxyBeanMethods = false)
class ChannelConfig {

    /**
     * Names the outbound client at both ends, so that neither the bean method's name nor an
     * injection point's parameter name is what ties them together.
     */
    private static final String SERVICE_CHANNEL_CLIENT = "serviceChannelRestClient";

    /**
     * A client of its own rather than a shared one. Its timeouts are chosen for a destination
     * this service does not control and cannot trust to answer, which is not the posture any
     * other outbound call in this application would want.
     *
     * <p>Which is why it is not a default candidate: were it one, a later call to some other
     * service asking for a {@code RestClient} by type would silently inherit a five-second read
     * timeout nobody chose for it. Only an injection point that names this client gets it.
     *
     * @param builder    Boot's builder, carrying the customizers this client must keep
     * @param properties the configured timeouts
     * @return the client the SERVICE channel posts with
     */
    @Bean(defaultCandidate = false)
    @Qualifier(SERVICE_CHANNEL_CLIENT)
    RestClient serviceChannelRestClient(RestClient.Builder builder,
            NotificationsProperties properties) {
        // Boot's builder rather than RestClient.builder(), and the difference is not cosmetic:
        // the static factory skips every customizer, including the one that instruments the call.
        // The inbound side is metered automatically; this is the only call that leaves the
        // process, and the only one whose latency the resilience requirement is about.
        return builder
                .requestFactory(requestFactoryFor(properties.channels().service()))
                .build();
    }

    /**
     * Shared with the channel's own test, so that what the test measures is the client this
     * service actually posts with. A test that assembles a client of its own proves how
     * <em>that</em> one reports a timeout, and the classification under test is built entirely on
     * how a timeout arrives.
     *
     * <p>Both timeouts matter, and the read one most: the connect timeout only covers a
     * destination that never accepts the connection, and a destination that accepts and then
     * goes quiet is the more common failure -- and the more expensive, because it holds a worker.
     *
     * @param service the configured timeouts
     * @return the factory the SERVICE channel posts through
     */
    static ClientHttpRequestFactory requestFactoryFor(NotificationsProperties.Channels.Service service) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(service.connectTimeout());
        requestFactory.setReadTimeout(service.readTimeout());
        return requestFactory;
    }

    /**
     * @param serviceChannelRestClient the client built above, asked for by name because it is no
     *                                 longer offered to anyone who does not
     * @return the SERVICE channel, ready to be found by the registry
     */
    @Bean
    NotificationChannelSender httpServiceChannelSender(
            @Qualifier(SERVICE_CHANNEL_CLIENT) RestClient serviceChannelRestClient) {
        return new HttpServiceChannelSender(serviceChannelRestClient);
    }
}
