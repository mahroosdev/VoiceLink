package com.mahroosdev.voicelink.config;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import com.mahroosdev.voicelink.auth.CurrentCredentialFilter;
import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.auth.AccountSessionRegistry;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2Password4jPasswordEncoder(
                Argon2Function.getInstance(65536, 3, 1, 32, Argon2.ID));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountRepository accounts,
                                            AccountSessionRegistry sessions) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/", "/about", "/login", "/register", "/forgot-password",
                        "/reset-password", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/login", "/register", "/forgot-password",
                        "/reset-password").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/css/voicelink.css").permitAll()
                .requestMatchers(HttpMethod.GET, "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, "/js/password-reset.js").permitAll()
                .requestMatchers(HttpMethod.GET, "/js/password-visibility.js").permitAll()
                .requestMatchers(HttpMethod.GET, "/js/room-live.js").authenticated()
                .requestMatchers(HttpMethod.GET, "/js/room-glossary.js").authenticated()
                .requestMatchers(HttpMethod.GET, "/js/delete-account.js").authenticated()
                .requestMatchers(HttpMethod.GET, "/ws/rooms/*").authenticated()
                .requestMatchers("/app", "/app/**", "/account", "/account/**",
                        "/rooms", "/rooms/**", "/api/**").authenticated()
                .requestMatchers("/ws/**").denyAll()
                .anyRequest().denyAll())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .usernameParameter("email")
                    .successHandler((request, response, authentication) -> {
                        AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
                        sessions.register(principal.getUserId(), request.getSession());
                        response.sendRedirect(request.getContextPath() + "/app");
                    })
                    .failureUrl("/login?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .addFilterAfter(new CurrentCredentialFilter(accounts, sessions), SecurityContextHolderFilter.class);
        return http.build();
    }
}
