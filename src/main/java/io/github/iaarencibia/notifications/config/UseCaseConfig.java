package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.service.SubmitNotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            NotificationsProperties properties) {
        return new SubmitNotificationService(repository, properties.retry().maxAttempts());
    }
}
