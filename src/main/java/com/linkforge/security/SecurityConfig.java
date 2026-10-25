package com.linkforge.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    /** false in prod: docs are denied at the filter chain before reaching springdoc. */
    @Value("${linkforge.docs-enabled:true}")
    private boolean docsEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        String[] docsPaths = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};
        String[] consolePaths = {"/", "/index.html", "/favicon.ico"};
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/api/health").permitAll()
                            .requestMatchers("/api/auth/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/r/**").permitAll()
                            .requestMatchers(HttpMethod.HEAD, "/r/**").permitAll()
                            // B3 unlock form: anonymous by design, brute-force limited in the service
                            .requestMatchers(HttpMethod.POST, "/r/*/unlock").permitAll();
                    if (docsEnabled) {
                        auth.requestMatchers(docsPaths).permitAll()
                                .requestMatchers(consolePaths).permitAll();
                    } else {
                        // prod: deny docs and the static console at the filter chain;
                        // add-mappings=false alone leaves "/" erroring with 500.
                        auth.requestMatchers(docsPaths).denyAll()
                                .requestMatchers(consolePaths).denyAll();
                    }
                    // B-wave2 B4 explicit capability whitelist: API keys may use the
                    // link/stats surface only; key and webhook management plus actuator
                    // stay JWT-only, and everything else defaults to JWT-only as well.
                    auth.requestMatchers("/api/links/**").hasAnyRole("USER", "API_KEY")
                            .requestMatchers("/api/keys/**", "/api/webhooks/**").hasRole("USER")
                            .requestMatchers("/actuator/**").hasRole("USER")
                            .anyRequest().hasRole("USER");
                })
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
                        })
                        // API keys are ROLE_API_KEY: anything not explicitly allowed above
                        // (key/webhook management, actuator, anything else) is a JSON 403.
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"forbidden\",\"data\":null}");
                        }))
                // JWT first (Bearer parsing), then the X-API-Key filter; either one may
                // establish the SecurityContext. Both together is a 400 from the key filter.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyAuthFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
