package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.service.SubmitNotificationService;
import io.github.iaarencibia.notifications.domain.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Where the use cases are assembled.
 *
 * <p>They are wired from here rather than annotated with {@code @Service} so that the application
 * layer carries no framework import at all, and takes its configuration as plain values instead of
 * as a bound properties object. That keeps the dependency pointing one way: this package knows the
 * use cases, and the use cases do not know this package exists.
 *
 * <p>It is also the single page a reader can open to see what this application is made of.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    SubmitNotificationUseCase submitNotificationUseCase(NotificationRepository repository,
            NotificationsProperties properties, DeliverableChannels deliverableChannels) {
        return new SubmitNotificationService(repository, properties.retry().maxAttempts(),
                deliverableChannels);
    }

    /**
     * What this deployment can send, derived from the adapters that are actually present rather
     * than from a list written somewhere. The day an EMAIL adapter is added it becomes
     * deliverable, and no validation and no error message has to be found and brought up to
     * date -- which is the failure this shape exists to prevent, since anything that has to be
     * kept in step eventually is not.
     *
     * <p>A bean rather than a private helper, because the web adapter needs the same answer to
     * tell a caller what it may send. One object, so the decision and its explanation cannot
     * come apart.
     *
     * @param senders every channel adapter wired into this process
     * @return the channels those adapters answer for
     */
    @Bean
    DeliverableChannels deliverableChannels(List<NotificationChannelSender> senders) {
        Set<Channel> channels = EnumSet.noneOf(Channel.class);
        for (NotificationChannelSender sender : senders) {
            if (!channels.add(sender.channel())) {
                // Refused at startup rather than resolved silently: with two senders claiming one
                // channel, which of them delivers would be settled by the order Spring happens to
                // return the beans in, and nobody chose that.
                throw new IllegalStateException(
                        "more than one sender is registered for channel " + sender.channel());
            }
        }
        return new DeliverableChannels(channels);
    }
}
