package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mahroosdev.voicelink.auth.TestPasswordResetNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetDisabledTests {
    @Autowired MockMvc mvc;
    @Autowired TestPasswordResetNotifier notifier;

    @Test
    void disabledRecoveryIsNotAdvertisedAndCannotIssueOrConsumeTokens() throws Exception {
        notifier.clear();
        String login = mvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(login).doesNotContain("Forgot password?");
        mvc.perform(get("/forgot-password")).andExpect(status().isNotFound());
        mvc.perform(get("/reset-password")).andExpect(status().isNotFound());
        mvc.perform(post("/forgot-password").with(csrf()).param("email", "someone@example.test"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/reset-password").with(csrf()).param("token", "A".repeat(43))
                .param("newPassword", "new-password-456").param("confirmPassword", "new-password-456"))
                .andExpect(status().isNotFound());
        assertThat(notifier.poll(200)).isNull();
    }
}
