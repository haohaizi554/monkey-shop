package com.example.monkey.config;

import com.example.monkey.security.SessionAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Session identity authentication does not use default users");
        };
    }

    @Bean
    public SessionAuthenticationFilter sessionAuthenticationFilter() {
        return new SessionAuthenticationFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionAuthenticationFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .addFilterBefore(sessionAuthenticationFilter, BasicAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/shop.html",
                                "/admin.html",
                                "/orders.html",
                                "/profile.html",
                                "/favicon.ico",
                                "/css/**",
                                "/js/**",
                                "/images/**")
                        .permitAll()
                        .requestMatchers("/api/auth/captcha", "/api/auth/register", "/api/auth/login",
                                "/api/auth/reset-password")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/monkeys", "/api/user/me")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/upload")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/address/**", "/api/user/captcha", "/api/user/profile",
                                "/api/user/update-avatar", "/api/user/update-password", "/api/user/logout",
                                "/api/orders/create", "/api/orders/my", "/api/orders/receive/**",
                                "/api/orders/return/apply/**", "/api/orders/return/ship/**")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/monkeys/add", "/api/monkeys/update", "/api/stats/**",
                                "/api/orders/all", "/api/orders/ship/**", "/api/orders/return/approve/**",
                                "/api/orders/return/confirm/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/monkeys/**", "/api/orders/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .denyAll())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' https://cdn.jsdelivr.net; "
                                        + "style-src 'self' https://cdn.jsdelivr.net; "
                                        + "img-src 'self' data:; "
                                        + "font-src 'self' data: https://cdn.jsdelivr.net; "
                                        + "connect-src 'self'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31536000))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
