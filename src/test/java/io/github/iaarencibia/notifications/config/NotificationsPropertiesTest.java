package io.github.iaarencibia.notifications.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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

    private static final String[] VALID_INSTANCE_ID = {
            "notifications.instance-id=notification-service-1"};
    private static final String[] VALID_SECURITY = {
            "notifications.security.api-key=a-development-key"};
    private static final String[] VALID_DISPATCH = {
            "notifications.dispatch.poll-interval=1s",
            "notifications.dispatch.batch-size=25",
            "notifications.dispatch.worker-pool-size=8",
            "notifications.dispatch.worker-queue-capacity=100"};
    private static final String[] VALID_REAPER = {
            "notifications.reaper.stale-claim-timeout=5m",
            "notifications.reaper.interval=1m"};
    private static final String[] VALID_RETRY = {
            "notifications.retry.max-attempts=3",
            "notifications.retry.initial-backoff=5s",
            "notifications.retry.multiplier=3.0",
            "notifications.retry.max-backoff=5m",
            "notifications.retry.jitter=0.2"};
    private static final String[] VALID_CHANNELS = {
            "notifications.channels.service.connect-timeout=2s",
            "notifications.channels.service.read-timeout=5s"};

    @Test
    @DisplayName("binds a complete configuration onto the typed properties")
    void bindsCompleteConfiguration() {
        complete().run(context -> {
            assertThat(context).hasNotFailed();

            NotificationsProperties properties = context.getBean(NotificationsProperties.class);
            assertThat(properties.instanceId()).isEqualTo("notification-service-1");
            assertThat(properties.security().apiKey()).isEqualTo("a-development-key");

            assertThat(properties.dispatch().pollInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.dispatch().batchSize()).isEqualTo(25);
            assertThat(properties.dispatch().workerPoolSize()).isEqualTo(8);
            assertThat(properties.dispatch().workerQueueCapacity()).isEqualTo(100);

            assertThat(properties.reaper().staleClaimTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.reaper().interval()).isEqualTo(Duration.ofMinutes(1));

            assertThat(properties.retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.retry().initialBackoff()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.retry().multiplier()).isEqualTo(3.0);
            assertThat(properties.retry().maxBackoff()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.retry().jitter()).isEqualTo(0.2);

            assertThat(properties.channels().service().connectTimeout())
                    .isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.channels().service().readTimeout())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("refuses to start when the channel timeouts are absent altogether")
    void rejectsMissingChannelsBlock() {
        runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_DISPATCH, VALID_REAPER, VALID_RETRY)
                .run(failsNaming("channels", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start on a read timeout of zero, which means waiting forever")
    void rejectsANonPositiveReadTimeout() {
        // Not a pedantic bound. A client with no read timeout keeps a worker thread parked on a
        // destination that accepted the connection and went quiet, and with a bounded pool that
        // is how one slow destination stops every other notification waiting behind it.
        complete().withPropertyValues("notifications.channels.service.read-timeout=0s")
                .run(failsNaming("readTimeout", "must be positive"));
    }

    @Test
    @DisplayName("refuses to start when only one of the two channel timeouts is configured")
    void rejectsAHalfConfiguredChannel() {
        // The positivity guard skips a null and leaves it to @NotNull, which is right and which
        // nothing checks: swapping that condition would let a half-configured channel bind, and
        // the missing timeout would become "no timeout" at the first slow destination.
        runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_DISPATCH, VALID_REAPER, VALID_RETRY)
                .withPropertyValues("notifications.channels.service.connect-timeout=2s")
                .run(failsNaming("readTimeout", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start on a connect timeout of zero as well")
    void rejectsANonPositiveConnectTimeout() {
        // Asserted separately from its sibling: one guard covering both would look identical in
        // the code and leave whichever half is untested free to be deleted.
        complete().withPropertyValues("notifications.channels.service.connect-timeout=0s")
                .run(failsNaming("connectTimeout", "must be positive"));
    }

    @Test
    @DisplayName("accepts an instance id exactly at the column width")
    void acceptsInstanceIdAtColumnWidth() {
        complete().withPropertyValues(instanceIdOf(CLAIMED_BY_WIDTH))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("refuses to start when the instance id exceeds the column width")
    void rejectsInstanceIdOverColumnWidth() {
        complete().withPropertyValues(instanceIdOf(CLAIMED_BY_WIDTH + 1))
                .run(failsNaming("instanceId", "size must be between"));
    }

    @Test
    @DisplayName("refuses to start when the instance id is blank")
    void rejectsBlankInstanceId() {
        complete().withPropertyValues("notifications.instance-id=   ")
                .run(failsNaming("instanceId", "must not be blank"));
    }

    @Test
    @DisplayName("refuses to start when the api key is blank")
    void rejectsBlankApiKey() {
        complete().withPropertyValues("notifications.security.api-key=")
                .run(failsNaming("apiKey", "must not be blank"));
    }

    @Test
    @DisplayName("refuses to start when the security block is absent altogether")
    void rejectsMissingSecurityBlock() {
        runnerWith(VALID_INSTANCE_ID, VALID_DISPATCH, VALID_REAPER, VALID_RETRY, VALID_CHANNELS)
                .run(failsNaming("security", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start on a delivery budget below one")
    void rejectsABudgetBelowOne() {
        // A budget of zero would create notifications that can never be attempted: they would sit
        // PENDING forever, accepted at the endpoint and never delivered, with nothing in the logs
        // to say why. The same rule is a check constraint on the column and a guard in the
        // aggregate; this is the only one of the three that fires before any traffic arrives.
        complete().withPropertyValues("notifications.retry.max-attempts=0")
                .run(failsNaming("maxAttempts", "must be greater than or equal to 1"));
    }

    @Test
    @DisplayName("refuses to start when the retry block is absent altogether")
    void rejectsMissingRetryBlock() {
        runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_DISPATCH, VALID_REAPER, VALID_CHANNELS)
                .run(failsNaming("retry", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start when the dispatch block is absent altogether")
    void rejectsMissingDispatchBlock() {
        runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_REAPER, VALID_RETRY, VALID_CHANNELS)
                .run(failsNaming("dispatch", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start on a poll interval of zero")
    void rejectsANonPositivePollInterval() {
        // Zero would turn the scheduler's fixed delay into a spin: the worker would poll an empty
        // table as fast as the connection pool allows, and the backlog it is supposed to drain
        // would be competing with it for connections.
        complete().withPropertyValues("notifications.dispatch.poll-interval=0s")
                .run(failsNaming("pollInterval", "must be positive"));
    }

    @Test
    @DisplayName("refuses to start on a batch size below one")
    void rejectsABatchSizeBelowOne() {
        // The silent failure this prevents: a batch of zero claims nothing on every poll, so the
        // worker runs forever, logs nothing unusual, and delivers nothing.
        complete().withPropertyValues("notifications.dispatch.batch-size=0")
                .run(failsNaming("batchSize", "must be greater than or equal to 1"));
    }

    @Test
    @DisplayName("refuses to start on a worker pool below one")
    void rejectsAWorkerPoolBelowOne() {
        // Silent in the same way, one step further along: rows would be claimed and queued, and
        // nothing would ever take them off the queue. They would sit DISPATCHING until the reaper
        // released them, and be claimed again by the same idle instance.
        complete().withPropertyValues("notifications.dispatch.worker-pool-size=0")
                .run(failsNaming("workerPoolSize", "must be greater than or equal to 1"));
    }

    @Test
    @DisplayName("refuses to start when the reaper block is absent altogether")
    void rejectsMissingReaperBlock() {
        runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_DISPATCH, VALID_RETRY, VALID_CHANNELS)
                .run(failsNaming("reaper", "must not be null"));
    }

    @Test
    @DisplayName("refuses to start on a stale claim timeout of zero")
    void rejectsANonPositiveStaleClaimTimeout() {
        // Zero would declare every claim abandoned the instant it was taken, so the reaper would
        // return rows to PENDING while the instance that owns them is still mid-delivery. That is
        // duplicate delivery on purpose, which is the one thing the timeout exists to bound.
        complete().withPropertyValues("notifications.reaper.stale-claim-timeout=0s")
                .run(failsNaming("staleClaimTimeout", "must be positive"));
    }

    @Test
    @DisplayName("refuses to start on a reaper interval of zero")
    void rejectsANonPositiveReaperInterval() {
        // Asserted separately from the timeout for the same reason the channel timeouts are: two
        // guards that look identical in the code, and whichever one nothing covers is free to be
        // deleted.
        complete().withPropertyValues("notifications.reaper.interval=0s")
                .run(failsNaming("interval", "must be positive"));
    }

    private static String instanceIdOf(int length) {
        return "notifications.instance-id=" + "x".repeat(length);
    }

    /**
     * Every block present and valid. A case about one particular value overrides that key on top;
     * a case about a whole block being absent builds the runner from the blocks it keeps, because
     * there is no property value that expresses "not configured".
     *
     * @return a runner that binds successfully as it stands
     */
    private static ApplicationContextRunner complete() {
        return runnerWith(VALID_INSTANCE_ID, VALID_SECURITY, VALID_DISPATCH, VALID_REAPER,
                VALID_RETRY, VALID_CHANNELS);
    }

    /**
     * @param blocks the configuration subtrees to declare, each as its own property values
     * @return a runner declaring exactly those
     */
    private static ApplicationContextRunner runnerWith(String[]... blocks) {
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(BindProperties.class);
        for (String[] block : blocks) {
            runner = runner.withPropertyValues(block);
        }
        return runner;
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
