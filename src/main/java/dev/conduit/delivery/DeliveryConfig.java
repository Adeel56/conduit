package dev.conduit.delivery;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the delivery engine (CON-13).
 *
 * <ul>
 *   <li>{@code @EnableScheduling} turns on the {@link DeliveryWorker}'s {@code @Scheduled} poll.</li>
 *   <li>{@code @EnableAsync} lets the fan-out run {@code @Async} off the ingest request thread.</li>
 * </ul>
 *
 * <p>The outbound {@link HttpClient} is built once and reused. It is configured for safety:
 * a connect timeout from {@link DeliveryProperties} (a hung TCP connect must not block a worker),
 * and {@link HttpClient.Redirect#NEVER} — Conduit <b>never</b> chases redirects, because a
 * redirect is a trivial SSRF pivot (a destination could 302 a worker at {@code 169.254.169.254} or
 * a private address). The per-request read timeout is set on each {@code HttpRequest} in the worker.
 *
 * <p>Blocking sends run on virtual threads (ADR-0004): the client's executor is a
 * virtual-thread-per-task executor, and the {@code @Async} fan-out uses one too, so simple
 * synchronous/blocking delivery code scales to many concurrent in-flight calls without a reactive
 * rewrite.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class DeliveryConfig {

    /**
     * The shared outbound HTTP client. Virtual-thread executor (ADR-0004), connect timeout from
     * config, and NEVER follow redirects (SSRF guard).
     */
    @Bean
    public HttpClient deliveryHttpClient(DeliveryProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Executor backing {@code @Async} so the fan-out leaves the ingest request thread immediately and
     * runs on a virtual thread. Bean name {@code applicationTaskExecutor} so Spring's async + scheduling
     * infrastructure picks it up as the default executor.
     */
    @Bean(name = "applicationTaskExecutor")
    public Executor deliveryAsyncExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * The worker's poll interval in millis, exposed as a simple named bean so the {@code @Scheduled}
     * {@code fixedDelayString} SpEL can reference it without a circular dependency on the worker bean
     * itself (referencing the worker while it is being created fails) and without depending on the
     * generated bean name of the {@link DeliveryProperties} record.
     */
    @Bean(name = "deliveryPollIntervalMillis")
    public Long deliveryPollIntervalMillis(DeliveryProperties properties) {
        return properties.pollInterval().toMillis();
    }
}
