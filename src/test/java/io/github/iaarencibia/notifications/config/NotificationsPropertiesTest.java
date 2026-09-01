package io.github.iaarencibia.notifications.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is under test is not the annotations but the moment they take effect: an unusable
 * value has to stop the application from starting.
 *
 * <p>It matters most for {@code instanceId}, which is written to
 * {@code notification.claimed_by} on every claim. Without a bound limit the value binds
 * happily and then fails every claim with the service already serving traffic, which is the
 * worst moment to discover it. The context is asserted rather than a {@code Validator},
 * because "the application refuses to start" is the actual claim.
 */
class NotificationsPropertiesTest {

    /**
     * Mirrors the width of {@code notification.claimed_by}. Declared here rather than read
     * from the production constant so that widening the column has to be done in both places
     * deliberately; a test that derives its expectation from the code under test proves
     * nothing.
     */
    private static final int CLAIMED_BY_WIDTH = 128;

    private static final String VALID_INSTANCE_ID = "notifications.instance-id=notification-service-1";
    private static final String VALID_API_KEY = "notifications.security.api-key=a-development-key";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindProperties.class);

    @Test
    @DisplayName("binds a complete configuration onto the typed properties")
    void bindsCompleteConfiguration() {
        runner.withPropertyValues(VALID_INSTANCE_ID, VALID_API_KEY).run(context -> {
            assertThat(context).hasNotFailed();

            NotificationsProperties properties = context.getBean(NotificationsProperties.class);
            assertThat(properties.instanceId()).isEqualTo("notification-service-1");
            assertThat(properties.security().apiKey()).isEqualTo("a-development-key");
        });
    }

    @Test
    @DisplayName("accepts an instance id exactly at the column width")
    void acceptsInstanceIdAtColumnWidth() {
        runner.withPropertyValues(instanceIdOf(CLAIMED_BY_WIDTH), VALID_API_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("refuses to start when the instance id exceeds the column width")
    void rejectsInstanceIdOverColumnWidth() {
        runner.withPropertyValues(instanceIdOf(CLAIMED_BY_WIDTH + 1), VALID_API_KEY)
                .run(failsNaming("instanceId", "size must be between"));
    }

    @Test
    @DisplayName("refuses to start when the instance id is blank")
    void rejectsBlankInstanceId() {
        runner.withPropertyValues("notifications.instance-id=   ", VALID_API_KEY)
                .run(failsNaming("instanceId", "must not be blank"));
    }

    @Test
    @DisplayName("refuses to start when the api key is blank")
    void rejectsBlankApiKey() {
        runner.withPropertyValues(VALID_INSTANCE_ID, "notifications.security.api-key=")
                .run(failsNaming("apiKey", "must not be blank"));
    }

    @Test
    @DisplayName("refuses to start when the security block is absent altogether")
    void rejectsMissingSecurityBlock() {
        runner.withPropertyValues(VALID_INSTANCE_ID).run(failsNaming("security", "must not be null"));
    }

    private static String instanceIdOf(int length) {
        return "notifications.instance-id=" + "x".repeat(length);
    }

    /**
     * Asserts that startup failed <em>for the stated reason</em>. The property name alone is not
     * enough: {@code security} is a substring of every failure under that subtree, so a violation
     * of the nested {@code apiKey} would satisfy a test claiming to prove the one on
     * {@code security} itself. The constraint message is what tells the two apart.
     *
     * @param fragments the property name and the constraint message the failure must name
     * @return the assertion to run against the started context
     */
    private static ContextConsumer<AssertableApplicationContext> failsNaming(String... fragments) {
        return context -> {
            assertThat(context).hasFailed();
            for (String fragment : fragments) {
                assertThat(context.getStartupFailure()).hasStackTraceContaining(fragment);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationsProperties.class)
    static class BindProperties {
    }
}
