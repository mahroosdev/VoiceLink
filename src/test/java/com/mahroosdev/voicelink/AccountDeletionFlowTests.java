package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountDeletionService;
import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionFlowTests {
    private static final String PASSWORD = "account deletion test passphrase";

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired AccountDeletionService deletion;
    @Autowired UserAccountRepository accounts;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired RoomParticipantRepository participants;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID createAccount() {
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("Delete Test");
        form.setEmail(UUID.randomUUID() + "@example.test");
        form.setPassword(PASSWORD);
        return registration.register(form);
    }

    private MockHttpSession login(UUID userId) throws Exception {
        String email = accounts.findById(userId).orElseThrow().getEmail();
        return (MockHttpSession) mvc.perform(post("/login").param("email", email)
                .param("password", PASSWORD).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/app"))
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void accountPagesRequireAuthenticationAndDestructiveGetIsRejected() throws Exception {
        mvc.perform(get("/account")).andExpect(status().isFound());
        mvc.perform(get("/account/delete")).andExpect(status().isFound());
        mvc.perform(post("/account/delete").with(csrf()).param("currentPassword", PASSWORD)
                .param("confirmation", "DELETE")).andExpect(status().isFound());
        UUID id = createAccount();
        MockHttpSession session = login(id);
        mvc.perform(get("/account").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Danger zone")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/account/delete")));
        mvc.perform(get("/account/delete").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("autocomplete=\"current-password\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Type DELETE to confirm")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"submit\" disabled")));
        assertThat(accounts.findById(id)).isPresent();
    }

    @Test
    void csrfWrongPasswordAndWrongConfirmationLeaveAccountAndSessionUsable() throws Exception {
        UUID id = createAccount();
        MockHttpSession session = login(id);
        mvc.perform(post("/account/delete").session(session).param("currentPassword", PASSWORD)
                .param("confirmation", "DELETE")).andExpect(status().isForbidden());
        mvc.perform(post("/account/delete").session(session).with(csrf())
                .param("currentPassword", "wrong password").param("confirmation", "DELETE"))
                .andExpect(status().isOk());
        mvc.perform(post("/account/delete").session(session).with(csrf())
                .param("currentPassword", PASSWORD).param("confirmation", "delete"))
                .andExpect(status().isOk());
        assertThat(accounts.findById(id)).isPresent();
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/account").session(session)).andExpect(status().isOk());
    }

    @Test
    void successfulDeletionInvalidatesEveryKnownSessionAndCredentials() throws Exception {
        UUID id = createAccount();
        String email = accounts.findById(id).orElseThrow().getEmail();
        MockHttpSession first = login(id);
        MockHttpSession second = login(id);
        mvc.perform(post("/account/delete").session(first).with(csrf())
                .param("currentPassword", PASSWORD).param("confirmation", "DELETE"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?deleted"));
        assertThat(accounts.findById(id)).isEmpty();
        assertThat(first.isInvalid()).isTrue();
        assertThat(second.isInvalid()).isTrue();
        mvc.perform(post("/login").param("email", email).param("password", PASSWORD).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
        assertThat(deletion.delete(id, PASSWORD, "DELETE")).isFalse();
    }

    @Test
    void deletionClosesJoinedRoomAndPreservesOtherAccountAndClosedRoom() {
        UUID creator = createAccount();
        UUID departing = createAccount();
        UUID roomId = rooms.createRoom(creator, "en", "ta");
        rooms.joinRoom(departing, roomRepository.findById(roomId).orElseThrow().getJoinCode());
        assertThat(deletion.delete(departing, PASSWORD, "DELETE")).isTrue();
        assertThat(accounts.findById(creator)).isPresent();
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        assertThat(participants.existsByRoom_IdAndUser_Id(roomId, departing)).isFalse();
        assertThat(participants.existsByRoom_IdAndUser_Id(roomId, creator)).isTrue();
    }

    @Test
    void deletionClosesAndRemovesCreatedRoomWithoutTouchingUnrelatedRoom() {
        UUID departing = createAccount();
        UUID counterpart = createAccount();
        UUID unrelated = createAccount();
        UUID created = rooms.createRoom(departing, "en", "ta");
        rooms.joinRoom(counterpart, roomRepository.findById(created).orElseThrow().getJoinCode());
        UUID untouched = rooms.createRoom(unrelated, "ta", "en");
        assertThat(deletion.delete(departing, PASSWORD, "DELETE")).isTrue();
        assertThat(roomRepository.findById(created)).isEmpty();
        assertThat(accounts.findById(counterpart)).isPresent();
        assertThat(roomRepository.findById(untouched).orElseThrow().getStatus()).isEqualTo(RoomStatus.WAITING);
    }

    @Test
    void rollbackLeavesAccountRoomAndSessionIntact() throws Exception {
        UUID owner = createAccount();
        UUID roomId = rooms.createRoom(owner, "en", "ta");
        MockHttpSession session = login(owner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(deletion.delete(owner, PASSWORD, "DELETE")).isTrue();
            status.setRollbackOnly();
        });
        assertThat(accounts.findById(owner)).isPresent();
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/account").session(session)).andExpect(status().isOk());
    }
}
