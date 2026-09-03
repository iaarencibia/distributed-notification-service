package io.github.iaarencibia.notifications.adapter.in.scheduler;

import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The timer that drives the whole thing.
 *
 * <p>It is the least interesting class in the change and the one whose absence is hardest to
 * notice: nothing this class does has a result anybody asserts on, so registering only one of the
 * two passes -- or none -- leaves every other test in the suite green while the delivered service
 * either never dispatches or never recovers abandoned work.
 */
class DispatchSchedulerConfigTest {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration REAPER_INTERVAL = Duration.ofMinutes(1);

    private final CountingDispatcher dispatcher = new CountingDispatcher();
    private final CountingReaper reaper = new CountingReaper();

    @Test
    @DisplayName("registers both passes, each at its own configured interval")
    void registersBothPasses() {
        ScheduledTaskRegistrar registrar = configuredRegistrar();

        assertThat(registrar.getFixedDelayTaskList())
                .as("a dispatcher with no reaper never recovers the work of an instance that died")
                .hasSize(2);
        assertThat(registrar.getFixedDelayTaskList().stream().map(task -> task.getIntervalDuration()))
                .containsExactly(POLL_INTERVAL, REAPER_INTERVAL);
    }

    @Test
    @DisplayName("the passes it registers are the dispatch and the reaper, in that order")
    void registersTheRightPasses() {
        // Asserted by running them, because the intervals alone would still match if both tasks
        // called the same use case twice.
        ScheduledTaskRegistrar registrar = configuredRegistrar();

        registrar.getFixedDelayTaskList().getFirst().getRunnable().run();
        assertThat(dispatcher.passes).isEqualTo(1);
        assertThat(reaper.passes).isZero();

        registrar.getFixedDelayTaskList().get(1).getRunnable().run();
        assertThat(reaper.passes).isEqualTo(1);
    }

    @Test
    @DisplayName("a pass that fails does not escape, so the timer survives it")
    void aFailedPassDoesNotEndTheSchedule() {
        // A database that is briefly unreachable would otherwise end the schedule for the life of
        // the process: the service would keep running and stop delivering, with one stack trace
        // to explain it.
        ScheduledTaskRegistrar registrar = registrarFor(() -> {
            throw new IllegalStateException("the database is unreachable");
        });

        assertThatCode(() -> registrar.getFixedDelayTaskList().getFirst().getRunnable().run())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is registered by default, and only switched off deliberately")
    void isOnUnlessTurnedOff() {
        // The tests turn it off for every context they boot, so nothing else would notice if the
        // condition were inverted and the delivered service shipped without a timer.
        contextRunner().run(context ->
                assertThat(context).hasSingleBean(DispatchSchedulerConfig.class));

        contextRunner().withPropertyValues("notifications.dispatch.scheduled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DispatchSchedulerConfig.class));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DispatchSchedulerConfig.class)
                .withBean(DispatchDueNotificationsUseCase.class, () -> dispatcher)
                .withBean(ReleaseAbandonedClaimsUseCase.class, () -> reaper)
                .withBean(NotificationsProperties.class, DispatchSchedulerConfigTest::properties);
    }

    private ScheduledTaskRegistrar configuredRegistrar() {
        return registrarFor(dispatcher::dispatchDue);
    }

    /**
     * @param pass what the dispatch use case should do when its task runs
     * @return a registrar the configuration has already written its tasks into
     */
    private ScheduledTaskRegistrar registrarFor(Runnable pass) {
        DispatchSchedulerConfig config = new DispatchSchedulerConfig(() -> {
            pass.run();
            return 0;
        }, reaper, properties());

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        config.configureTasks(registrar);
        return registrar;
    }

    /**
     * @return a complete configuration whose two intervals differ, so a task registered with the
     *         wrong one is visible
     */
    private static NotificationsProperties properties() {
        return new NotificationsProperties("notification-service-1",
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Dispatch(POLL_INTERVAL, 25, 8, 100),
                new NotificationsProperties.Reaper(Duration.ofMinutes(5), REAPER_INTERVAL),
                new NotificationsProperties.Retry(3, Duration.ofSeconds(5), 3.0,
                        Duration.ofMinutes(5), 0.2),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(Duration.ofSeconds(2),
                                Duration.ofSeconds(5))));
    }

    private static final class CountingDispatcher implements DispatchDueNotificationsUseCase {

        private int passes;

        @Override
        public int dispatchDue() {
            passes++;
            return 0;
        }
    }

    private static final class CountingReaper implements ReleaseAbandonedClaimsUseCase {

        private int passes;

        @Override
        public int releaseAbandonedClaims() {
            passes++;
            return 0;
        }
    }
}
