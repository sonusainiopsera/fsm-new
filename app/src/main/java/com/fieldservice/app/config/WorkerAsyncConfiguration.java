package com.fieldservice.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Async/scheduling configuration registered exclusively under the {@code worker} profile.
 *
 * <p>Beans in this class are ABSENT when the application runs with the {@code api}
 * profile only. Schedulers and outbox pollers never exist on the request-serving
 * deployable.</p>
 *
 * <p>The worker profile also sets {@code spring.main.web-application-type=none}
 * in {@code application-worker.yml} so no business HTTP connector starts.
 * The management port (Actuator) is still available for health scraping.</p>
 *
 * <p>Registered beans:</p>
 * <ul>
 *   <li>{@link TaskScheduler} — virtual-thread-backed scheduler for all worker tasks.</li>
 *   <li>{@link OutboxPoller} — polls the transactional outbox every 500 ms.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
public class WorkerAsyncConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerAsyncConfiguration.class);

    /**
     * Task scheduler backed by virtual threads for the worker profile.
     * Thread names are prefixed with {@code worker-scheduler-} for observability.
     */
    @Bean(name = "workerTaskScheduler")
    public TaskScheduler workerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("worker-scheduler-");
        scheduler.setVirtualThreads(true);
        scheduler.initialize();
        log.info("Initialized virtual-thread task scheduler for worker profile");
        return scheduler;
    }

    /**
     * Outbox poller bean present only in the worker profile.
     * Polls the transactional outbox table every 500 ms using
     * {@code FOR UPDATE SKIP LOCKED} to safely distribute work across worker replicas.
     */
    @Bean
    public OutboxPoller outboxPoller() {
        return new OutboxPoller();
    }

    /**
     * Placeholder outbox poller.
     * Full implementation (JDBC + batch publish) is delivered in the outbox story.
     */
    public static class OutboxPoller {

        private static final Logger pollerLog = LoggerFactory.getLogger(OutboxPoller.class);

        /**
         * Polls the outbox table every 500 ms.
         * Annotation-driven scheduling is enabled by {@link EnableScheduling} on
         * {@link WorkerAsyncConfiguration}.
         */
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 500)
        public void poll() {
            pollerLog.debug("Outbox poller tick — full implementation pending outbox story");
        }
    }
}
