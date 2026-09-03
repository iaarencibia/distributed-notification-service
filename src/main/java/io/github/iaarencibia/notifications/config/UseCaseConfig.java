package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.application.service.DispatchNotificationsService;
import io.github.iaarencibia.notifications.application.service.ReleaseAbandonedClaimsService;
import io.github.iaarencibia.notifications.application.service.SubmitNotificationService;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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

    /**
     * Names the delivery pool at both ends. It is not the only {@code Executor} in the context --
     * scheduling brings its own -- and it must never be resolved by type.
     */
    private static final String DISPATCH_WORKERS = "dispatchWorkers";

    @Bean
    SubmitNotificationUseCase submitNotificationUseCase(NotificationRepository repository,
            SystemClock clock, NotificationsProperties properties,
            DeliverableChannels deliverableChannels) {
        return new SubmitNotificationService(repository, clock, properties.retry().maxAttempts(),
                deliverableChannels);
    }

    /**
     * The schedule every reschedule follows, built once. Its own constructor is what refuses an
     * impossible one -- a multiplier that shrinks the delay, a ceiling below the first backoff --
     * and building it here means that refusal lands while the context starts.
     *
     * @param properties the configured schedule
     * @return the policy the aggregate consults when it reschedules
     */
    @Bean
    RetryPolicy retryPolicy(NotificationsProperties properties) {
        NotificationsProperties.Retry retry = properties.retry();
        return new RetryPolicy(retry.initialBackoff(), retry.multiplier(), retry.maxBackoff(),
                retry.jitter());
    }

    /**
     * @param queue      where work is claimed and results are written
     * @param senders    the adapter answering for each deliverable channel
     * @param clock      the database clock, the only one the application reads
     * @param retryPolicy the schedule a rescheduled notification follows
     * @param workers    runs the deliveries, bounded
     * @param properties the instance identity and the batch size
     * @return the dispatcher, ready for a scheduler to drive
     */
    @Bean
    DispatchDueNotificationsUseCase dispatchDueNotificationsUseCase(NotificationDispatchQueue queue,
            Map<Channel, NotificationChannelSender> senders, SystemClock clock,
            RetryPolicy retryPolicy, @Qualifier(DISPATCH_WORKERS) Executor workers,
            NotificationsProperties properties) {
        return new DispatchNotificationsService(queue, senders, clock, retryPolicy, workers,
                properties.instanceId(), properties.dispatch().batchSize());
    }

    /**
     * @param abandonedClaims where claims are released
     * @param properties      how long a claim may stand before its owner is presumed dead
     * @return the reaper
     */
    @Bean
    ReleaseAbandonedClaimsUseCase releaseAbandonedClaimsUseCase(AbandonedClaims abandonedClaims,
            NotificationsProperties properties) {
        return new ReleaseAbandonedClaimsService(abandonedClaims,
                properties.reaper().staleClaimTimeout());
    }

    /**
     * The pool the deliveries run on. Bounded in both dimensions on purpose: an unbounded pool
     * turns a slow destination into unbounded threads, and an unbounded queue turns it into
     * unbounded memory.
     *
     * <p>Not a default candidate. Scheduling contributes an {@code Executor} of its own, so by
     * type alone there is no answer; and its bounds and its saturation policy are chosen for
     * outbound deliveries, which is not a posture anything else should inherit by accident.
     *
     * @param properties the configured pool and queue sizes
     * @return the executor the dispatcher hands each delivery to
     */
    @Bean(defaultCandidate = false)
    @Qualifier(DISPATCH_WORKERS)
    Executor dispatchWorkers(NotificationsProperties properties) {
        ThreadPoolTaskExecutor workers = new ThreadPoolTaskExecutor();
        workers.setCorePoolSize(properties.dispatch().workerPoolSize());
        workers.setMaxPoolSize(properties.dispatch().workerPoolSize());
        workers.setQueueCapacity(properties.dispatch().workerQueueCapacity());
        workers.setThreadNamePrefix("dispatch-");
        // With the queue full, the polling thread performs the delivery itself. That is the
        // backpressure: while it is busy delivering it is not claiming more work, so the backlog
        // stays in the table -- which is durable -- instead of in memory.
        workers.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // A claimed notification whose delivery is dropped at shutdown stays DISPATCHING until the
        // reaper releases it, minutes later. Finishing what is in flight costs seconds.
        workers.setWaitForTasksToCompleteOnShutdown(true);
        workers.setAwaitTerminationSeconds(30);
        workers.initialize();
        return workers;
    }

    /**
     * The channel registry: which adapter answers for which channel.
     *
     * @param senders every channel adapter wired into this process
     * @return the adapters, keyed by the channel each answers for
     */
    @Bean
    Map<Channel, NotificationChannelSender> channelSenders(List<NotificationChannelSender> senders) {
        Map<Channel, NotificationChannelSender> byChannel = new EnumMap<>(Channel.class);
        for (NotificationChannelSender sender : senders) {
            if (byChannel.putIfAbsent(sender.channel(), sender) != null) {
                // Refused at startup rather than resolved silently: with two senders claiming one
                // channel, which of them delivers would be settled by the order Spring happens to
                // return the beans in, and nobody chose that.
                throw new IllegalStateException(
                        "more than one sender is registered for channel " + sender.channel());
            }
        }
        return Map.copyOf(byChannel);
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
     * @param channelSenders the registry above
     * @return the channels those adapters answer for
     */
    @Bean
    DeliverableChannels deliverableChannels(
            Map<Channel, NotificationChannelSender> channelSenders) {
        return new DeliverableChannels(channelSenders.keySet());
    }
}
