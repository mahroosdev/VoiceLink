package com.mahroosdev.voicelink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.auth.TestPasswordResetNotifier;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "voicelink.password-reset.enabled=true",
        "voicelink.password-reset.public-base-url=https://voicelink.example.test",
        "voicelink.password-reset.mail-api-key=offline-only",
        "voicelink.password-reset.mail-from-address=noreply@example.test",
        "voicelink.abuse.login-per-address-per-minute=2",
        "voicelink.abuse.login-per-instance-per-minute=1000",
        "voicelink.abuse.registration-per-address-per-minute=2",
        "voicelink.abuse.registration-per-instance-per-minute=1000",
        "voicelink.abuse.password-reset-per-address-per-minute=2",
        "voicelink.abuse.password-reset-per-instance-per-minute=1000",
        "voicelink.abuse.room-creation-per-account-per-minute=1",
        "voicelink.abuse.room-creation-per-instance-per-minute=1000",
        "voicelink.abuse.invitation-per-address-per-minute=2",
        "voicelink.abuse.invitation-per-account-per-minute=3",
        "voicelink.abuse.invitation-per-instance-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndpointAbuseFlowTests {
    private static final String PASSWORD = "test-only-password-2026";

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository accounts;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRows;
    @Autowired TestPasswordResetNotifier notifier;

    @BeforeEach void clearTestNotifier() { notifier.clear(); }

    @Test
    void csrfIsCheckedBeforeLoginQuotaAndForwardedHeadersCannotChangeAddressBucket() throws Exception {
        String address = "198.51.100.21";
        mvc.perform(post("/login").with(remote(address, "203.0.113.1"))
                        .param("email", "unknown@example.test").param("password", "not-a-password"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).with(remote(address, "203.0.113.1"))
                        .param("email", "unknown@example.test").param("password", "not-a-password"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).with(remote(address, "192.0.2.99"))
                        .param("email", "different@example.test").param("password", "not-a-password"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).with(remote(address, "192.0.2.99"))
                        .param("email", "third@example.test").param("password", "not-a-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
        mvc.perform(post("/login").with(csrf()).with(remote("198.51.100.22", "192.0.2.99"))
                        .param("email", "different-address@example.test").param("password", "not-a-password"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void registrationIsAddressLimitedAndStillAllowsRequestsBelowTheLimit() throws Exception {
        String address = "198.51.100.31";
        register(address, UUID.randomUUID() + "@example.test").andExpect(status().isFound());
        register(address, UUID.randomUUID() + "@example.test").andExpect(status().isFound());
        register(address, UUID.randomUUID() + "@example.test").andExpect(status().isTooManyRequests());
    }

    @Test
    void enabledPasswordResetStaysGenericAndUsesTheOfflineNotifierOnly() throws Exception {
        String address = "198.51.100.41";
        String first = mvc.perform(post("/forgot-password").with(csrf()).with(remote(address, null))
                        .param("email", "unknown@example.test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(first).contains("If an account exists for that email, password reset instructions have been sent.")
                .doesNotContain("unknown@example.test", "not found");
        assertThat(notifier.poll(10)).isNull();
        mvc.perform(post("/reset-password").with(csrf()).with(remote(address, null))
                        .param("token", "invalid-token").param("newPassword", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isOk());
        mvc.perform(post("/forgot-password").with(csrf()).with(remote(address, null))
                        .param("email", "another@example.test"))
                .andExpect(status().isTooManyRequests());
        assertThat(notifier.poll(10)).isNull();
    }

    @Test
    void roomCreationIsLimitedByAuthenticatedAccountAcrossAddresses() throws Exception {
        AccountPrincipal principal = newAccount("room-limit");
        mvc.perform(post("/rooms").with(user(principal)).with(csrf()).with(remote("198.51.100.51", null))
                        .param("displayName", "Test Room").param("speaking", "en").param("profile", "STANDARD"))
                .andExpect(status().isFound());
        mvc.perform(post("/rooms").with(user(principal)).with(csrf()).with(remote("198.51.100.52", null))
                        .param("displayName", "Another Room").param("speaking", "ta").param("profile", "STANDARD"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void invitationAttemptsAreLimitedForAddressAndAccountAndAnonymousRequestsDoNotConsumeQuota() throws Exception {
        AccountPrincipal owner = newAccount("invite-owner");
        UUID roomId = rooms.createRoom(owner.getUserId(), "en", "ta");
        String code = roomRows.findById(roomId).orElseThrow().getJoinCode();
        String address = "198.51.100.61";
        mvc.perform(post("/rooms/join/preview").with(remote(address, null)).with(csrf()).param("joinCode", code))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login"));

        AccountPrincipal joiner = newAccount("invite-joiner");
        mvc.perform(post("/rooms/join/preview").with(user(joiner)).with(csrf()).with(remote(address, null))
                        .param("joinCode", code)).andExpect(status().isOk());
        mvc.perform(post("/rooms/join/preview").with(user(joiner)).with(csrf()).with(remote(address, null))
                        .param("joinCode", code)).andExpect(status().isOk());
        mvc.perform(post("/rooms/join/preview").with(user(joiner)).with(csrf()).with(remote("198.51.100.62", null))
                        .param("joinCode", code)).andExpect(status().isOk());
        mvc.perform(post("/rooms/join/preview").with(user(joiner)).with(csrf()).with(remote("198.51.100.63", null))
                        .param("joinCode", code)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/rooms/join/preview").with(user(newAccount("invite-third"))).with(csrf())
                        .with(remote(address, null)).param("joinCode", code))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions register(String address, String email)
            throws Exception {
        return mvc.perform(post("/register").with(csrf()).with(remote(address, null))
                .param("displayName", "Abuse Test").param("email", email).param("password", PASSWORD));
    }

    private AccountPrincipal newAccount(String prefix) {
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("Abuse Test");
        form.setEmail(prefix + "+" + UUID.randomUUID() + "@example.test");
        form.setPassword(PASSWORD);
        UUID id = registration.register(form);
        UserAccount account = accounts.findById(id).orElseThrow();
        return new AccountPrincipal(id, account.getEmail(), account.getPasswordHash(), true);
    }

    private static RequestPostProcessor remote(String address, String forwardedFor) {
        return request -> {
            request.setRemoteAddr(address);
            if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
            return request;
        };
    }
}
