package dev.conduit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Conduit service.
 *
 * <p>{@code @SpringBootApplication} bundles {@code @Configuration},
 * {@code @EnableAutoConfiguration} (Spring Boot wires beans from what is on the classpath — web,
 * Actuator, JPA, Flyway), and {@code @ComponentScan} (this package and below). Virtual threads
 * are switched on in {@code application.yml}, not here.
 */
@SpringBootApplication
public class ConduitApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConduitApplication.class, args);
    }
}
