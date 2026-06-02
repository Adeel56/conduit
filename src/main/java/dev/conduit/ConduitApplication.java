package dev.conduit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Conduit service.
 *
 * <p>{@code @SpringBootApplication} bundles {@code @Configuration},
 * {@code @EnableAutoConfiguration} (Spring Boot wires beans from what is on the classpath — web,
 * Actuator, JPA, Flyway), and {@code @ComponentScan} (this package and below). Virtual threads
 * are switched on in {@code application.yml}, not here.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds {@code @ConfigurationProperties} records (e.g.
 * {@code IngestProperties}) from configuration.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConduitApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConduitApplication.class, args);
    }
}
