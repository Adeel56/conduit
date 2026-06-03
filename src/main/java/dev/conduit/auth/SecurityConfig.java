package dev.conduit.auth;

import dev.conduit.apikey.ApiKeyService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security policy for the whole service (CON-8): stateless API-key auth, secure-by-default.
 *
 * <p><b>Public allowlist</b> — {@code /health} (+ probes) and {@code /ingest/**} stay open; the
 * ingest endpoint is public-by-secret-URL (CON-7) and must never require an API key. Everything
 * else requires a valid key. CSRF is disabled because this is a stateless token API (also necessary
 * so {@code POST /ingest} keeps working), and no session is created.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyService apiKeyService) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Don't "secure" the framework error dispatch: a binding/conversion failure
                        // (e.g. a non-UUID {id}) forwards to /error on an ERROR dispatch where the
                        // API-key filter doesn't re-run, so without this it would surface as a
                        // misleading 401. Permitting ERROR lets the real status (e.g. 400) through;
                        // the original REQUEST dispatch still enforces auth (no key -> 401).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/health", "/health/**").permitAll()
                        .requestMatchers("/ingest/**").permitAll()
                        .anyRequest().authenticated())
                // Resolve the API key into a principal before the username/password slot.
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new RestAuthenticationEntryPoint()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
