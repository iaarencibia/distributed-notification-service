package io.github.iaarencibia.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the notification hub.
 *
 * <p>Scheduling is enabled here, at the outermost edge of the application, because the
 * dispatch worker is an inbound adapter that drives the application: it is a delivery
 * mechanism, not a piece of business logic. Keeping the annotation out of the use cases
 * is what allows the scheduler to be replaced (for example by a Jakarta EE timer) without
 * touching the core.
 */
// The in-memory user Boot generates when no UserDetailsService exists is excluded: nothing in
// the chain can authenticate against it, and leaving it in prints a credential on every start.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
