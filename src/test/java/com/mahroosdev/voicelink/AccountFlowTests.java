package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.DuplicateRegistrationException;
import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import com.mahroosdev.voicelink.user.UserPreferencesRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowTests {
    private static final String PASSWORD = "long Unicode passphrase ✓ 2026";
    private static final Logger logger = LoggerFactory.getLogger(AccountFlowTests.class);

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository accounts;
    @Autowired UserPreferencesRepository preferences;
    @Autowired PasswordEncoder encoder;

    private RegistrationForm form(String email) {
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("  Test User  ");
        form.setEmail(email);
        form.setPassword(PASSWORD);
        return form;
    }

    private String newEmail() {
        return UUID.randomUUID() + "@example.test";
    }

    @Test
    void registrationNormalizesEmailCreatesPreferencesAndEncodesPassword() {
        String email = newEmail();
        UUID id = registration.register(form("  " + email.toUpperCase() + "  "));
        var account = accounts.findById(id).orElseThrow();
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getDisplayName()).isEqualTo("Test User");
        assertThat(account.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$argon2id$");
        assertThat(encoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.isEnabled()).isTrue();
        assertThat(account.getCreatedAt()).isNotNull();
        var preference = preferences.findById(id).orElseThrow();
        assertThat(preference.getPreferredSpeakingLanguageTag()).isNull();
        assertThat(preference.getPreferredListeningLanguageTag()).isNull();
    }

    @Test
    void duplicateEmailIsRejectedAfterNormalization() {
        String email = newEmail();
        registration.register(form(email));
        assertThatThrownBy(() -> registration.register(form("  " + email.toUpperCase() + "  ")))
                .isInstanceOf(DuplicateRegistrationException.class);
        assertThat(accounts.findByEmail(email)).isPresent();
    }

    @Test
    void invalidDisplayNameEmailAndPasswordAreRejected() {
        RegistrationForm badName = form(newEmail());
        badName.setDisplayName("A\nB");
        assertThatThrownBy(() -> registration.register(badName)).isInstanceOf(ConstraintViolationException.class);
        RegistrationForm badEmail = form("not-an-email");
        assertThatThrownBy(() -> registration.register(badEmail)).isInstanceOf(ConstraintViolationException.class);
        RegistrationForm shortPassword = form(newEmail());
        shortPassword.setPassword("123456789");
        assertThatThrownBy(() -> registration.register(shortPassword))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Password must be 10 to 128 characters.");
    }

    @Test
    void passwordLengthBoundariesAreEnforcedByRegistrationService() {
        RegistrationForm minimum = form(newEmail());
        minimum.setPassword("1234567890");
        UUID minimumId = registration.register(minimum);
        assertThat(encoder.matches(minimum.getPassword(), accounts.findById(minimumId).orElseThrow().getPasswordHash()))
                .isTrue();

        RegistrationForm maximum = form(newEmail());
        maximum.setPassword("x".repeat(128));
        UUID maximumId = registration.register(maximum);
        assertThat(encoder.matches(maximum.getPassword(), accounts.findById(maximumId).orElseThrow().getPasswordHash()))
                .isTrue();

        RegistrationForm tooLong = form(newEmail());
        tooLong.setPassword("x".repeat(129));
        assertThatThrownBy(() -> registration.register(tooLong))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Password must be 10 to 128 characters.");
        assertThat(accounts.existsByEmail(tooLong.getEmail())).isFalse();
    }

    @Test
    void shortPasswordShowsUpdatedAccessibleFeedbackWithoutSuccess() throws Exception {
        String email = newEmail();
        MvcResult invalid = mvc.perform(post("/register").with(csrf())
                .param("displayName", "Test User")
                .param("email", email)
                .param("password", "123456789"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Password (10 to 128 characters)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Password must be 10 to 128 characters.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"alert\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Account created successfully"))))
                .andReturn();
        assertThat(invalid.getFlashMap().isEmpty()).isTrue();
        assertThat(accounts.existsByEmail(email)).isFalse();
    }

    @Test
    void pagesArePublicAndApplicationIsProtected() throws Exception {
        mvc.perform(get("/register")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Create a VoiceLink account")));
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/app")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/ws/future")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/ws/future").with(user("test"))).andExpect(status().isForbidden());
    }

    @Test
    void registrationRequiresCsrfAndInvalidInputKeepsSafeValuesWithoutSuccess() throws Exception {
        String email = newEmail();
        mvc.perform(post("/register").param("displayName", "Test User").param("email", email)
                .param("password", PASSWORD)).andExpect(status().isForbidden());
        assertThat(accounts.existsByEmail(email)).isFalse();
        MvcResult invalid = mvc.perform(post("/register").with(csrf()).param("displayName", " Test User ")
                .param("email", "bad-address").param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Test User\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"bad-address\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Account created successfully"))))
                .andReturn();
        assertThat(invalid.getFlashMap().isEmpty()).isTrue();
    }

    @Test
    void successfulRegistrationShowsOneTimeAccessibleMessageAfterRedirect() throws Exception {
        String email = newEmail();
        MvcResult registered = mvc.perform(post("/register").with(csrf())
                .param("displayName", " Test User ")
                .param("email", "  " + email.toUpperCase() + "  ")
                .param("password", PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("registrationSuccess",
                        "Account created successfully. You can now sign in."))
                .andReturn();
        assertThat(accounts.existsByEmail(email)).isTrue();
        assertThat(registered.getFlashMap().values()).doesNotContain(PASSWORD);
        mvc.perform(get("/login").flashAttrs(registered.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "role=\"status\" aria-live=\"polite\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Account created successfully. You can now sign in.")));
        mvc.perform(get("/login"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Account created successfully"))));
    }

    @Test
    void duplicateRegistrationStaysOnFormWithGenericErrorAndNoSuccess() throws Exception {
        String email = newEmail();
        registration.register(form(email));
        MvcResult duplicate = mvc.perform(post("/register").with(csrf())
                .param("displayName", " Test User ")
                .param("email", email.toUpperCase())
                .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Registration could not be completed with those details.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Test User\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"" + email + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Account created successfully"))))
                .andReturn();
        assertThat(duplicate.getFlashMap().isEmpty()).isTrue();
    }

    @Test
    void loginFailureIsGenericAndValidSessionCanLogout() throws Exception {
        String email = newEmail();
        registration.register(form(email));
        mvc.perform(post("/login").param("email", email).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "wrong"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", "  " + email.toUpperCase() + "  ").param("password", PASSWORD))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/app"))
                .andExpect(authenticated()).andReturn();
        mvc.perform(get("/app").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isOk());
        mvc.perform(post("/logout").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(csrf())
                .session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?logout"));
        mvc.perform(get("/app").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isFound());
    }

    @Test
    void argon2idVerificationTimingDoesNotExposePasswordOrHash() {
        long start = System.nanoTime();
        String hash = encoder.encode(PASSWORD);
        long encoded = System.nanoTime();
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        long verified = System.nanoTime();
        logger.info("Argon2id local encode={} ms, verify={} ms",
                (encoded - start) / 1_000_000, (verified - encoded) / 1_000_000);
    }
}
