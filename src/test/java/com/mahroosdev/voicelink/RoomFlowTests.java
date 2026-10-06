package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomInviteUnavailableException;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomFlowTests {
    @Autowired RoomService service;
    @Autowired ConversationRoomRepository rooms;
    @Autowired RoomParticipantRepository participants;
    @Autowired UserAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private UserAccount account(String name) {
        return accounts.save(new UserAccount(name, UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    private UserDetails principal(UserAccount account) {
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash(), true);
    }

    @Test
    void creationIsAtomicWaitingAndHasSlotOneAndSecureCode() {
        UserAccount creator = account("Creator");
        UUID id = service.createRoom(creator.getId(), " EN ", "TA");
        var room = rooms.findById(id).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.getCreatedBy().getId()).isEqualTo(creator.getId());
        assertThat(room.getJoinCode()).matches("[0-9A-HJKMNP-TV-Z]{16}");
        assertThat(room.getInviteExpiresAt()).isEqualTo(room.getCreatedAt().plusSeconds(24 * 60 * 60));
        var members = participants.findByRoom_IdOrderByParticipantSlotAsc(id);
        assertThat(members).hasSize(1);
        assertThat(members.getFirst().getUser().getId()).isEqualTo(creator.getId());
        assertThat(members.getFirst().getParticipantSlot()).isEqualTo((short) 1);
        assertThat(members.getFirst().getSpeakingLanguageTag()).isEqualTo("en");
        assertThat(members.getFirst().getListeningLanguageTag()).isEqualTo("ta");
    }

    @Test
    void invalidDirectionCreatesNoRoom() {
        UserAccount creator = account("Creator");
        assertThatThrownBy(() -> service.createRoom(creator.getId(), "en", "en"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.listRoomsForMember(creator.getId())).isEmpty();
    }

    @Test
    void joinUsesReverseDirectionAndCodeIsNoLongerAvailable() {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UUID id = service.createRoom(creator.getId(), "ta", "en");
        String code = rooms.findById(id).orElseThrow().getJoinCode().toLowerCase();
        String grouped = code.substring(0, 4) + "-" + code.substring(4, 8) + "-"
                + code.substring(8, 12) + "-" + code.substring(12);
        assertThat(service.joinRoom(joiner.getId(), grouped)).isEqualTo(id);
        assertThat(rooms.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
        var members = participants.findByRoom_IdOrderByParticipantSlotAsc(id);
        assertThat(members).hasSize(2);
        assertThat(members.get(1).getParticipantSlot()).isEqualTo((short) 2);
        assertThat(members.get(1).getUser().getId()).isEqualTo(joiner.getId());
        assertThat(members.get(1).getSpeakingLanguageTag()).isEqualTo("en");
        assertThat(members.get(1).getListeningLanguageTag()).isEqualTo("ta");
        assertThat(service.getRoomForMember(joiner.getId(), id).inviteCode()).isNull();
        assertThat(service.getRoomForMember(creator.getId(), id).inviteCode()).isNull();
    }

    @Test
    void existingMemberMayReturnButThirdUserCannotJoin() {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UserAccount third = account("Third");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        assertThat(service.joinRoom(creator.getId(), code)).isEqualTo(id);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(1);
        service.joinRoom(joiner.getId(), code);
        assertThat(service.joinRoom(joiner.getId(), code)).isEqualTo(id);
        assertThatThrownBy(() -> service.joinRoom(third.getId(), code))
                .isInstanceOf(RoomInviteUnavailableException.class);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(2);
    }

    @Test
    void missingExpiredAndClosedInvitesAreRejected() {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        assertThatThrownBy(() -> service.joinRoom(joiner.getId(), "BAD"))
                .isInstanceOf(RoomInviteUnavailableException.class);
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        Instant now = Instant.now();
        jdbc.update("UPDATE conversation_rooms SET created_at = ?, invite_expires_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(172800)), Timestamp.from(now.minusSeconds(86400)), id);
        assertThatThrownBy(() -> service.joinRoom(joiner.getId(), code))
                .isInstanceOf(RoomInviteUnavailableException.class);
        assertThatThrownBy(() -> service.joinRoom(creator.getId(), code))
                .isInstanceOf(RoomInviteUnavailableException.class);
        service.closeRoom(creator.getId(), id);
        assertThatThrownBy(() -> service.joinRoom(joiner.getId(), code))
                .isInstanceOf(RoomInviteUnavailableException.class);
        assertThatThrownBy(() -> service.joinRoom(creator.getId(), code))
                .isInstanceOf(RoomInviteUnavailableException.class);
    }

    @Test
    void onlyMembersViewRoomAndListsStayPrivate() {
        UserAccount creator = account("Creator");
        UserAccount outsider = account("Outsider");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        assertThat(service.getRoomForMember(creator.getId(), id).participants()).hasSize(1);
        assertThat(service.listRoomsForMember(creator.getId())).extracting("id").contains(id);
        assertThat(service.listRoomsForMember(outsider.getId())).isEmpty();
        assertThatThrownBy(() -> service.getRoomForMember(outsider.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.getRoomForMember(creator.getId(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void closeRequiresMembershipAndCannotReopen() {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UserAccount outsider = account("Outsider");
        UUID waitingId = service.createRoom(creator.getId(), "en", "ta");
        assertThatThrownBy(() -> service.closeRoom(outsider.getId(), waitingId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        service.closeRoom(creator.getId(), waitingId);
        assertThat(rooms.findById(waitingId).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        assertThat(rooms.findById(waitingId).orElseThrow().getClosedAt()).isNotNull();
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(waitingId)).hasSize(1);
        service.closeRoom(creator.getId(), waitingId);
        assertThatThrownBy(() -> service.joinRoom(joiner.getId(), rooms.findById(waitingId).orElseThrow().getJoinCode()))
                .isInstanceOf(RoomInviteUnavailableException.class);
        UUID activeId = service.createRoom(creator.getId(), "en", "ta");
        service.joinRoom(joiner.getId(), rooms.findById(activeId).orElseThrow().getJoinCode());
        service.closeRoom(joiner.getId(), activeId);
        assertThat(rooms.findById(activeId).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(activeId)).hasSize(2);
    }

    @Test
    void inviteAgeDoesNotCloseActiveRoomAndRefreshOrLogoutDoesNotMutateIt() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        service.joinRoom(joiner.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        jdbc.update("UPDATE conversation_rooms SET created_at = ?, invite_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(172800)),
                Timestamp.from(Instant.now().minusSeconds(86400)), id);
        mvc.perform(get("/rooms/" + id).with(user(principal(joiner))))
                .andExpect(status().isOk());
        mvc.perform(post("/logout").with(user(principal(joiner))).with(csrf()))
                .andExpect(status().isFound());
        assertThat(rooms.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
    }

    @Test
    void browserRoutesUsePrincipalAndHidePrivateRooms() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount outsider = account("Outsider");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        mvc.perform(get("/rooms")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/rooms").with(user(principal(creator)))).andExpect(status().isOk());
        mvc.perform(get("/rooms/" + id).with(user(principal(creator)))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Creator")));
        mvc.perform(get("/rooms/" + id).with(user(principal(outsider)))).andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Creator"))));
        mvc.perform(get("/rooms/" + UUID.randomUUID()).with(user(principal(outsider))))
                .andExpect(status().isNotFound());
    }

    @Test
    void createJoinAndCloseRequireCsrfAndIgnoreSubmittedUserId() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        mvc.perform(post("/rooms").with(user(principal(creator))).param("speaking", "en")
                .param("listening", "ta")).andExpect(status().isForbidden());
        mvc.perform(post("/rooms/join").with(user(principal(joiner))).param("joinCode", code))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rooms/" + id + "/close").with(user(principal(creator))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rooms/join").with(user(principal(joiner))).with(csrf())
                .param("joinCode", code).param("userId", creator.getId().toString()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms/" + id));
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id).get(1).getUser().getId())
                .isEqualTo(joiner.getId());
        mvc.perform(get("/ws/future").with(user(principal(joiner)))).andExpect(status().isForbidden());
    }

    @Test
    void webCreationValidationAndGenericJoinErrorAreSafe() throws Exception {
        UserAccount creator = account("Creator");
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("speaking", "en").param("listening", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose English to Tamil or Tamil to English.")));
        var result = mvc.perform(post("/rooms/join").with(user(principal(creator))).with(csrf())
                .param("joinCode", "missing"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms"))
                .andReturn();
        assertThat(result.getFlashMap().get("joinError"))
                .isEqualTo("Invite unavailable. Check the code or ask for a new room.");
        assertThat(service.listRoomsForMember(creator.getId())).isEmpty();
    }

    @Test
    void browserCanCreateJoinAndExplicitlyCloseUsingPrincipalIdentity() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UserAccount outsider = account("Outsider");
        String redirect = mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("speaking", "en").param("listening", "ta")
                .param("userId", outsider.getId().toString()))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        UUID id = UUID.fromString(redirect.substring("/rooms/".length()));
        assertThat(rooms.findById(id).orElseThrow().getCreatedBy().getId()).isEqualTo(creator.getId());
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        mvc.perform(get(redirect).with(user(principal(creator))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Waiting for the second participant")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("and receive")));
        mvc.perform(post("/rooms/join").with(user(principal(joiner))).with(csrf()).param("joinCode", code))
                .andExpect(status().isFound()).andExpect(redirectedUrl(redirect));
        mvc.perform(get(redirect).with(user(principal(joiner))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Room ready")));
        mvc.perform(post(redirect + "/close").with(user(principal(outsider))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(redirect + "/close").with(user(principal(joiner))).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl(redirect));
        assertThat(rooms.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        mvc.perform(get(redirect).with(user(principal(creator))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cannot be reopened")));
    }
}
