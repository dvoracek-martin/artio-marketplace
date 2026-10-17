package io.artio.marketplace.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  UserDetailsService noPasswordAuthentication() {
    return username -> {
      throw new UsernameNotFoundException("Password authentication is not configured");
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/marketplace/**")
                    .permitAll()
                    // The controller applies distinct constant-time API-key checks for each role.
                    .requestMatchers("/api/v1/admin/marketplace/**")
                    .permitAll()
                    // Handlers are registered only by marketplace-local-dev; without the profile
                    // these routes have no mapping and return 404.
                    .requestMatchers("/api/v1/dev-admin/**", "/dev-admin/**")
                    .permitAll()
                    .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .headers(headers -> headers.contentTypeOptions(Customizer.withDefaults()))
        .build();
  }
}
