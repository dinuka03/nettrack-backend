package com.nettrack.backend.config;

import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkInterfaceStats;
import com.nettrack.backend.model.NetworkRateResult;
import com.nettrack.backend.repository.NetworkIntervalUsageRepository;
import com.nettrack.backend.service.NetworkMonitoringScheduler;
import com.nettrack.backend.service.NetworkMonitoringStateHolder;
import com.nettrack.backend.service.NetworkRateCalculatorService;
import com.nettrack.backend.service.NetworkSamplingService;
import com.nettrack.backend.service.ProcNetDevReaderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMonitoringConfigTest {

    private final CapturingTaskScheduler capturingTaskScheduler = new CapturingTaskScheduler();
    private final AtomicInteger sampleCallCount = new AtomicInteger(0);

    @Configuration
    static class TestInfrastructureConfig {

        @Bean
        public TaskScheduler taskScheduler(CapturingTaskScheduler scheduler) {
            return scheduler;
        }

        @Bean
        public NetworkSamplingService networkSamplingService(AtomicInteger counter) {
            return new NetworkSamplingService(
                    new ProcNetDevReaderService() {
                        @Override
                        public NetworkInterfaceStats readStats() {
                            return null;
                        }
                    },
                    new NetworkRateCalculatorService()
            ) {
                @Override
                public synchronized Optional<NetworkRateResult> sampleRate() throws IOException {
                    counter.incrementAndGet();
                    return Optional.empty(); // Baseline sample
                }
            };
        }

        @Bean
        public NetworkIntervalUsageRepository networkIntervalUsageRepository() {
            return new NetworkIntervalUsageRepository(null) {
                @Override
                public long insert(NetworkRateResult result, Instant recordedAt) {
                    return 1L;
                }
            };
        }
    }

    private ApplicationContextRunner createContextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        TestInfrastructureConfig.class,
                        NetworkMonitoringConfig.class,
                        NetworkMonitoringScheduler.class,
                        NetworkMonitoringStateHolder.class
                )
                .withBean(CapturingTaskScheduler.class, () -> capturingTaskScheduler)
                .withBean(AtomicInteger.class, () -> sampleCallCount);
    }

    @Test
    @DisplayName("1. Missing enable property: no scheduler or config, state holder available in INITIAL state")
    void missingPropertyDisablesScheduler() {
        createContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NetworkMonitoringScheduler.class);
            assertThat(context).doesNotHaveBean(NetworkMonitoringConfig.class);
            assertThat(context).hasSingleBean(NetworkMonitoringStateHolder.class);

            NetworkMonitoringStateHolder holder = context.getBean(NetworkMonitoringStateHolder.class);
            assertEquals(MonitoringStatus.INITIAL, holder.getCurrentState().status());
            assertEquals(0, capturingTaskScheduler.tasks.size());
            assertEquals(0, sampleCallCount.get());
        });
    }

    @Test
    @DisplayName("2. Explicit false: no scheduler or config, state holder available in INITIAL state")
    void explicitFalseDisablesScheduler() {
        createContextRunner()
                .withPropertyValues("nettrack.monitoring.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(NetworkMonitoringScheduler.class);
                    assertThat(context).doesNotHaveBean(NetworkMonitoringConfig.class);
                    assertThat(context).hasSingleBean(NetworkMonitoringStateHolder.class);

                    NetworkMonitoringStateHolder holder = context.getBean(NetworkMonitoringStateHolder.class);
                    assertEquals(MonitoringStatus.INITIAL, holder.getCurrentState().status());
                    assertEquals(0, capturingTaskScheduler.tasks.size());
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("3. Explicit true with default delay: registers exactly one task with 1000ms fixed delay")
    void explicitTrueRegistersSingleTaskWithDefaultDelay() {
        createContextRunner()
                .withPropertyValues("nettrack.monitoring.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NetworkMonitoringScheduler.class);
                    assertThat(context).hasSingleBean(NetworkMonitoringConfig.class);
                    assertThat(context).hasSingleBean(NetworkMonitoringStateHolder.class);

                    assertEquals(1, capturingTaskScheduler.tasks.size(), "Exactly one fixed-delay task must be registered");
                    CapturingTaskScheduler.CapturedTask captured = capturingTaskScheduler.tasks.get(0);
                    assertEquals(Duration.ofMillis(1000), captured.delay());

                    // Exercise the captured callback to verify actual task wiring
                    NetworkMonitoringStateHolder holder = context.getBean(NetworkMonitoringStateHolder.class);
                    assertEquals(MonitoringStatus.INITIAL, holder.getCurrentState().status());

                    captured.runnable().run();

                    assertEquals(1, sampleCallCount.get(), "Scheduled callback must invoke sampler");
                    assertEquals(MonitoringStatus.BASELINE_ONLY, holder.getCurrentState().status());
                });
    }

    @Test
    @DisplayName("4. Custom positive delay is applied to the registered task")
    void customPositiveDelayApplied() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=2500"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals(1, capturingTaskScheduler.tasks.size());
                    assertEquals(Duration.ofMillis(2500), capturingTaskScheduler.tasks.get(0).delay());
                });
    }

    @Test
    @DisplayName("5. Zero interval fails startup clearly and never executes sampling")
    void zeroIntervalFailsStartup() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Property 'nettrack.monitoring.interval-ms' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: 0");
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("6. Negative interval fails startup clearly and never executes sampling")
    void negativeIntervalFailsStartup() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=-1"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Property 'nettrack.monitoring.interval-ms' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: -1");
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("7. Blank interval fails startup clearly and never executes sampling")
    void blankIntervalFailsStartup() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=   "
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Property 'nettrack.monitoring.interval-ms' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was blank");
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("8. Nonnumeric interval fails startup clearly and never executes sampling")
    void nonnumericIntervalFailsStartup() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=invalid_number"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Property 'nettrack.monitoring.interval-ms' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: 'invalid_number'");
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("9. Out-of-range interval fails startup clearly and never executes sampling")
    void outOfRangeIntervalFailsStartup() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=true",
                        "nettrack.monitoring.interval-ms=99999999999999999999999999999999"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Property 'nettrack.monitoring.interval-ms' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: '99999999999999999999999999999999'");
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("10. Disabled monitoring ignores an unused invalid interval setting")
    void disabledMonitoringIgnoresInvalidInterval() {
        createContextRunner()
                .withPropertyValues(
                        "nettrack.monitoring.enabled=false",
                        "nettrack.monitoring.interval-ms=-999"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(NetworkMonitoringScheduler.class);
                    assertThat(context).doesNotHaveBean(NetworkMonitoringConfig.class);
                    assertEquals(0, sampleCallCount.get());
                });
    }

    @Test
    @DisplayName("11. Closing the enabled context cancels the registered task")
    void closingContextCancelsRegisteredTask() {
        createContextRunner()
                .withPropertyValues("nettrack.monitoring.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals(1, capturingTaskScheduler.tasks.size());
                    CapturingTaskScheduler.CapturedTask task = capturingTaskScheduler.tasks.get(0);
                    assertFalse(task.future().isCancelled());

                    ((ConfigurableApplicationContext) context.getSourceApplicationContext()).close();
                    assertTrue(task.future().isCancelled(), "Task future must be cancelled when context is closed");
                });
    }

    // Capturing TaskScheduler implementation for testing
    static class CapturingTaskScheduler implements TaskScheduler {
        final List<CapturedTask> tasks = new ArrayList<>();

        record CapturedTask(Runnable runnable, Duration delay, TestScheduledFuture future) {
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, delay, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, delay, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, null, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, null, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, period, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            TestScheduledFuture future = new TestScheduledFuture();
            tasks.add(new CapturedTask(task, period, future));
            return future;
        }
    }

    static class TestScheduledFuture implements ScheduledFuture<Void> {
        private boolean cancelled = false;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Void get() {
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
