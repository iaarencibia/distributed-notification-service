package io.github.iaarencibia.notifications.adapter.in.scheduler;

import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.function.IntSupplier;

/**
 * The inbound adapter that drives the dispatcher: a timer, and nothing else.
 *
 * <p>The intervals are registered from the bound configuration rather than declared on
 * {@code @Scheduled} annotations, so that the values the scheduler runs on are the same validated
 * ones the rest of the application reads. An annotation would resolve the placeholder itself and
 * quietly become a second source for the same number.
 *
 * <p>Fixed delay and not fixed rate: the next pass starts after the previous one returns, so a
 * pass that runs long delays the next instead of overlapping it. The two passes are given a
 * thread each ({@code spring.task.scheduling.pool.size}) so that a dispatch pass running long --
 * which it does exactly when the workers are saturated -- cannot also hold up the reaper.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "notifications.dispatch", name = "scheduled",
        havingValue = "true", matchIfMissing = true)
class DispatchSchedulerConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DispatchSchedulerConfig.class);

    private final DispatchDueNotificationsUseCase dispatchDueNotifications;
    private final ReleaseAbandonedClaimsUseCase releaseAbandonedClaims;
    private final NotificationsProperties properties;

    DispatchSchedulerConfig(DispatchDueNotificationsUseCase dispatchDueNotifications,
            ReleaseAbandonedClaimsUseCase releaseAbandonedClaims,
            NotificationsProperties properties) {
        this.dispatchDueNotifications = dispatchDueNotifications;
        this.releaseAbandonedClaims = releaseAbandonedClaims;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::pollForDueWork, properties.dispatch().pollInterval());
        registrar.addFixedDelayTask(this::recoverAbandonedWork, properties.reaper().interval());
    }

    private void pollForDueWork() {
        run("dispatch", dispatchDueNotifications::dispatchDue);
    }

    private void recoverAbandonedWork() {
        int released = run("reaper", releaseAbandonedClaims::releaseAbandonedClaims);
        if (released > 0) {
            // Worth a line of its own: on a healthy system this never fires, so when it does it
            // says an instance died mid-delivery and those notifications may be delivered twice.
            log.warn("released {} abandoned claims", released);
        }
    }

    /**
     * Keeps the timer alive across a failed pass.
     *
     * <p>A database that is briefly unreachable must not end the schedule for the life of the
     * process: the pass that failed is lost, the next one is not.
     *
     * @param pass what this pass is called, for the log line
     * @param work the pass to run
     * @return what the pass reported, or zero if it failed
     */
    private int run(String pass, IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException failure) {
            log.warn("the {} pass failed and will be retried on the next tick", pass, failure);
            return 0;
        }
    }
}
