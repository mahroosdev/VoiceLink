package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.mahroosdev.voicelink.auth.PasswordResetService;
import com.mahroosdev.voicelink.auth.PasswordResetSettings;
import com.mahroosdev.voicelink.auth.PasswordResetTokenRepository;
import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.auth.TestPasswordResetNotifier;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "voicelink.password-reset.enabled=true",
        "voicelink.password-reset.public-base-url=https://voicelink.example.test",
        "voicelink.password-reset.mail-api-key=offline-only",
        "voicelink.password-reset.mail-from-address=noreply@example.test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetFlowTests {
    private static final String OLD_PASSWORD = "old-password-123";
    private static final String NEW_PASSWORD = "new-password-456";

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired PasswordResetService resets;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired TestPasswordResetNotifier notifier;

    @BeforeEach void clearMessages() { notifier.clear(); }

    private String account() {
        String email = UUID.randomUUID() + "@example.test";
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("Reset Test");
        form.setEmail(email);
        form.setPassword(OLD_PASSWORD);
        registration.register(form);
        return email;
    }

    private String requestToken(String email) throws Exception {
        String body = mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("If an account exists for that email, password reset instructions have been sent.");
        var message = notifier.await();
        assertThat(message).isNotNull();
        assertThat(message.resetUrl()).startsWith("https://voicelink.example.test/reset-password#token=");
        return message.token();
    }

    private String submit(String token, String password, String confirmation) throws Exception {
        return mvc.perform(post("/reset-password").with(csrf())
                .param("token", token).param("newPassword", password)
                .param("confirmPassword", confirmation))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anonymousPagesAndPublicScriptAreAvailableWithPrivateResetHeaders() throws Exception {
        String login = mvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(login).contains("/forgot-password", "Forgot password?");
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
        var reset = mvc.perform(get("/reset-password")).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(reset.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(reset.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(reset.getContentAsString()).contains("name=\"_csrf\"", "10–128 characters.");
        mvc.perform(get("/js/password-reset.js")).andExpect(status().isOk());
        mvc.perform(get("/js/room-live.js")).andExpect(status().isFound());
    }

    @Test
    void knownAndUnknownRequestsHaveTheSamePublicAcknowledgement() throws Exception {
        String email = account();
        String known = mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(notifier.await()).isNotNull();
        String unknown = mvc.perform(post("/forgot-password").with(csrf())
                .param("email", UUID.randomUUID() + "@example.test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains("If an account exists for that email, password reset instructions have been sent.");
        assertThat(unknown).isEqualTo(known);
        assertThat(known).doesNotContain(email);
        assertThat(unknown).doesNotContain("account not found");
        assertThat(notifier.poll(200)).isNull();
    }

    @Test
    void definiteMailFailureKeepsGenericResponseAndRemovesOnlyThatToken() throws Exception {
        String email = account();
        notifier.failNext();
        String body = mvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("If an account exists for that email, password reset instructions have been sent.")
                .doesNotContain("delivery failed", "Resend", "offline-only");
        var attempt = notifier.await();
        assertThat(attempt).isNotNull();
        UUID userId = accounts.findByEmail(email).orElseThrow().getId();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tokens.findById(userId).isPresent() && System.nanoTime() < until) Thread.sleep(20);
        assertThat(tokens.findById(userId)).isEmpty();
    }

    @Test
    void issuedTokenIsRandomHashedAndNotInTheInitialRequestUrl() throws Exception {
        String email = account();
        String raw = requestToken(email);
        var account = accounts.findByEmail(email).orElseThrow();
        var row = tokens.findById(account.getId()).orElseThrow();
        assertThat(raw).hasSize(43);
        assertThat(row.getTokenHash()).isEqualTo(PasswordResetService.hash(raw)).hasSize(64);
        assertThat(row.getTokenHash()).isNotEqualTo(raw);
        assertThat(row.getExpiresAt()).isAfter(row.getCreatedAt().plus(29, ChronoUnit.MINUTES));
    }

    @Test
    void validResetDeletesTokenAndOnlyNewPasswordAuthenticates() throws Exception {
        String email = account();
        String raw = requestToken(email);
        mvc.perform(post("/reset-password").with(csrf()).param("token", raw)
                .param("newPassword", NEW_PASSWORD).param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?reset"));
        var account = accounts.findByEmail(email).orElseThrow();
        assertThat(tokens.findById(account.getId())).isEmpty();
        assertThat(encoder.matches(OLD_PASSWORD, account.getPasswordHash())).isFalse();
        assertThat(encoder.matches(NEW_PASSWORD, account.getPasswordHash())).isTrue();
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", OLD_PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", NEW_PASSWORD))
                .andExpect(redirectedUrl("/app"));
        mvc.perform(get("/app")).andExpect(status().isFound());
        assertThat(submit(raw, NEW_PASSWORD, NEW_PASSWORD)).contains("invalid or has expired");
    }

    @Test
    void expiredMalformedAndDirectlyForgedTokensAreRejected() throws Exception {
        String email = account();
        String raw = requestToken(email);
        var token = tokens.findById(accounts.findByEmail(email).orElseThrow().getId()).orElseThrow();
        token.replace(token.getTokenHash(), Instant.now().minus(40, ChronoUnit.MINUTES),
                Instant.now().minus(10, ChronoUnit.MINUTES));
        tokens.saveAndFlush(token);
        assertThat(submit(raw, NEW_PASSWORD, NEW_PASSWORD)).contains("invalid or has expired");
        assertThat(submit("malformed", NEW_PASSWORD, NEW_PASSWORD)).contains("invalid or has expired");
        assertThat(submit("A".repeat(43), NEW_PASSWORD, NEW_PASSWORD)).contains("invalid or has expired");
        assertThat(encoder.matches(OLD_PASSWORD, accounts.findByEmail(email).orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    void newerTokenInvalidatesOlderToken() throws Exception {
        String email = account();
        String older = requestToken(email);
        String newer = requestToken(email);
        assertThat(older).isNotEqualTo(newer);
        assertThat(submit(older, NEW_PASSWORD, NEW_PASSWORD)).contains("invalid or has expired");
        mvc.perform(post("/reset-password").with(csrf()).param("token", newer)
                .param("newPassword", NEW_PASSWORD).param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isFound());
    }

    @Test
    void passwordBoundariesAndConfirmationMatchRegistrationPolicy() throws Exception {
        String email = account();
        String raw = requestToken(email);
        assertThat(submit(raw, "123456789", "123456789"))
                .contains("Password must be 10 to 128 characters.").doesNotContain("value=\"123456789\"");
        assertThat(submit(raw, "x".repeat(129), "x".repeat(129)))
                .contains("Password must be 10 to 128 characters.");
        assertThat(submit(raw, NEW_PASSWORD, "different-password"))
                .contains("Passwords must match.");
        assertThat(tokens.findById(accounts.findByEmail(email).orElseThrow().getId())).isPresent();
        mvc.perform(post("/reset-password").with(csrf()).param("token", raw)
                .param("newPassword", "1234567890").param("confirmPassword", "1234567890"))
                .andExpect(status().isFound());
        String other = account();
        String maxToken = requestToken(other);
        mvc.perform(post("/reset-password").with(csrf()).param("token", maxToken)
                .param("newPassword", "x".repeat(128)).param("confirmPassword", "x".repeat(128)))
                .andExpect(status().isFound());
    }

    @Test
    void csrfIsRequiredOnBothPostRoutes() throws Exception {
        String email = account();
        mvc.perform(post("/forgot-password").param("email", email)).andExpect(status().isForbidden());
        mvc.perform(post("/reset-password").param("token", "A".repeat(43))
                .param("newPassword", NEW_PASSWORD).param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(tokens.findById(accounts.findByEmail(email).orElseThrow().getId())).isEmpty();
    }

    @Test
    void oldAuthenticatedHttpSessionIsRejectedAfterReset() throws Exception {
        String email = account();
        var login = mvc.perform(post("/login").with(csrf()).param("email", email)
                .param("password", OLD_PASSWORD)).andExpect(redirectedUrl("/app"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/app").session(session)).andExpect(status().isOk());
        String raw = requestToken(email);
        mvc.perform(post("/reset-password").with(csrf()).param("token", raw)
                .param("newPassword", NEW_PASSWORD).param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isFound());
        mvc.perform(get("/app").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void tokenAndCredentialDoNotAppearInResponsesOrLogs(CapturedOutput output) throws Exception {
        String email = account();
        String raw = requestToken(email);
        String html = submit(raw, NEW_PASSWORD, "wrong-confirmation");
        assertThat(html).doesNotContain("offline-only", NEW_PASSWORD, "wrong-confirmation");
        assertThat(output.getAll()).doesNotContain(raw, "offline-only", NEW_PASSWORD);
    }

    @Test
    void concurrentUseAllowsExactlyOneReset() throws Exception {
        String raw = requestToken(account());
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> { start.await(); return resets.reset(raw, NEW_PASSWORD); });
            var second = workers.submit(() -> { start.await(); return resets.reset(raw, NEW_PASSWORD); });
            start.countDown();
            assertThat((first.get(8, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(8, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
    }

    @Test
    void concurrentIssuanceLeavesOneCurrentToken() throws Exception {
        String email = account();
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> { start.await(); resets.request(email); return null; });
            var second = workers.submit(() -> { start.await(); resets.request(email); return null; });
            start.countDown();
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
        }
        String currentHash = tokens.findById(accounts.findByEmail(email).orElseThrow().getId())
                .orElseThrow().getTokenHash();
        java.util.List<String> delivered = new java.util.ArrayList<>();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until && delivered.stream().noneMatch(token ->
                PasswordResetService.hash(token).equals(currentHash))) {
            var message = notifier.poll(100);
            if (message != null) delivered.add(message.token());
        }
        assertThat(delivered.stream().anyMatch(token -> PasswordResetService.hash(token).equals(currentHash)))
                .isTrue();
        for (String token : delivered) {
            if (!PasswordResetService.hash(token).equals(currentHash)) {
                assertThat(resets.reset(token, NEW_PASSWORD)).isFalse();
            }
        }
    }

    @Test
    void invalidEnabledConfigurationFailsClosed() {
        StandardEnvironment testEnvironment = new StandardEnvironment();
        testEnvironment.setActiveProfiles("test");
        assertThatThrownBy(() -> new PasswordResetSettings(true, "http://example.test", "offline-only",
                "noreply@example.test", testEnvironment)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PasswordResetSettings(true, "https://example.test", "",
                "noreply@example.test", testEnvironment)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PasswordResetSettings(true, "https://example.test", "offline-only",
                "", testEnvironment)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PasswordResetSettings(true, "https://example.test", "offline-only",
                "noreply@example.test", new StandardEnvironment())).isInstanceOf(IllegalStateException.class);
    }
}
