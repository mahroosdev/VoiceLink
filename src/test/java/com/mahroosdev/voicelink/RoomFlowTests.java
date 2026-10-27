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
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.room.PremiumUnavailableException;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomInviteUnavailableException;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomLiveAuthorizer;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomFlowTests {
    @Autowired RoomService service;
    @Autowired RoomLiveAuthorizer liveAuthorizer;
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
        assertThat(room.getServiceProfile()).isEqualTo(AiServiceProfile.STANDARD);
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
    void explicitStandardPersistsAndPremiumIsUnavailableInNormalTests() {
        UserAccount creator = account("Creator");
        UUID id = service.createRoom(creator.getId(), "en", "ta", AiServiceProfile.STANDARD);
        assertThat(rooms.findById(id).orElseThrow().getServiceProfile()).isEqualTo(AiServiceProfile.STANDARD);
        assertThatThrownBy(() -> service.createRoom(creator.getId(), "en", "ta", AiServiceProfile.PREMIUM))
                .isInstanceOf(PremiumUnavailableException.class);
        assertThat(service.listRoomsForMember(creator.getId())).hasSize(1);
    }

    @Test
    void forgedPremiumAndInvalidProfilePostAreRejectedWithoutCreatingRoom() throws Exception {
        UserAccount creator = account("Creator");
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("displayName", "Creator").param("speaking", "en").param("profile", "PREMIUM"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Premium profile is not currently available.")));
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("displayName", "Creator").param("speaking", "en").param("profile", "OTHER"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Choose a valid service profile.")));
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
    void ownerCanChangeBothParticipantDirectionsAtomicallyAfterJoin() {
        UserAccount owner = account("Language Owner");
        UserAccount partner = account("Language Partner");
        UUID id = service.createRoom(owner.getId(), "en", "ta");
        service.joinRoom(partner.getId(), rooms.findById(id).orElseThrow().getJoinCode());

        service.updateLanguages(owner.getId(), id, "ta");

        var members = participants.findByRoom_IdOrderByParticipantSlotAsc(id);
        assertThat(members).extracting("speakingLanguageTag", "listeningLanguageTag")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ta", "en"),
                        org.assertj.core.groups.Tuple.tuple("en", "ta"));
        assertThat(service.getRoomForMember(partner.getId(), id).ownerSpeaking()).isEqualTo("ta");
        assertThatThrownBy(() -> service.updateLanguages(partner.getId(), id, "en"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(404);
    }

    @Test
    void languageChangeRejectsInvalidDirectionsAndClosedRooms() {
        UserAccount owner = account("Language Owner");
        UUID id = service.createRoom(owner.getId(), "en", "ta");
        assertThatThrownBy(() -> service.updateLanguages(owner.getId(), id, "fr"))
                .isInstanceOf(IllegalArgumentException.class);
        service.closeRoom(owner.getId(), id);
        assertThatThrownBy(() -> service.updateLanguages(owner.getId(), id, "ta"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void persistedLanguageFenceAllowsCurrentWorkAndRejectsStaleOrClosedWork() {
        UserAccount owner = account("Fenced Owner");
        UserAccount partner = account("Fenced Partner");
        UUID id = service.createRoom(owner.getId(), "en", "ta");
        service.joinRoom(partner.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        var prior = liveAuthorizer.authorize(owner.getId(), id);
        var published = new java.util.concurrent.atomic.AtomicBoolean();

        assertThat(liveAuthorizer.runIfActiveLanguageVersion(owner.getId(), id, prior.participantId(),
                prior.languageVersion(), () -> published.set(true))).isTrue();
        assertThat(published).isTrue();

        // Simulate A -> B -> A after the operation's early authorization check.
        service.updateLanguages(owner.getId(), id, "ta");
        service.updateLanguages(owner.getId(), id, "en");
        published.set(false);
        assertThat(liveAuthorizer.runIfActiveLanguageVersion(owner.getId(), id, prior.participantId(),
                prior.languageVersion(), () -> published.set(true))).isFalse();
        assertThat(published).isFalse();

        service.closeRoom(owner.getId(), id);
        var current = liveAuthorizer.authorize(owner.getId(), id);
        assertThat(liveAuthorizer.runIfActiveLanguageVersion(owner.getId(), id, current.participantId(),
                current.languageVersion(), () -> published.set(true))).isFalse();
    }

    @Test
    void concurrentLanguageChangesSerializeAndKeepDirectionsComplementary() throws Exception {
        UserAccount owner = account("Concurrent language owner");
        UserAccount partner = account("Concurrent language partner");
        UUID id = service.createRoom(owner.getId(), "en", "ta");
        service.joinRoom(partner.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var english = workers.submit(() -> {
                ready.countDown(); start.await(); service.updateLanguages(owner.getId(), id, "en"); return null;
            });
            var tamil = workers.submit(() -> {
                ready.countDown(); start.await(); service.updateLanguages(owner.getId(), id, "ta"); return null;
            });
            assertThat(ready.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            english.get(5, java.util.concurrent.TimeUnit.SECONDS);
            tamil.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        var members = participants.findByRoom_IdOrderByParticipantSlotAsc(id);
        assertThat(members.get(0).getSpeakingLanguageTag())
                .isNotEqualTo(members.get(1).getSpeakingLanguageTag());
        assertThat(members.get(0).getListeningLanguageTag()).isEqualTo(members.get(1).getSpeakingLanguageTag());
        assertThat(members.get(1).getListeningLanguageTag()).isEqualTo(members.get(0).getSpeakingLanguageTag());
        assertThat(rooms.findById(id).orElseThrow().getLanguageVersion()).isEqualTo(2);
    }

    @Test
    void languageUpdateEndpointEnforcesOwnerAndCsrf() throws Exception {
        UserAccount owner = account("Endpoint Owner");
        UserAccount partner = account("Endpoint Partner");
        UUID id = service.createRoom(owner.getId(), "en", "ta");
        service.joinRoom(partner.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        String body = "{\"ownerSpeaking\":\"ta\"}";

        mvc.perform(post("/api/rooms/" + id + "/languages").with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rooms/" + id + "/languages").with(user(principal(partner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/rooms/" + id + "/languages").with(user(principal(owner)))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms/" + id + "/languages").with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ownerSpeaking\":\"fr\"}"))
                .andExpect(status().isBadRequest());
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
    void roomViewDerivesYouPartnerAndDirectionsFromAuthenticatedMembershipEvenWhenNamesMatch() {
        UserAccount creator = account("Same Name");
        UserAccount joiner = account("Same Name");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        service.joinRoom(joiner.getId(), rooms.findById(id).orElseThrow().getJoinCode());

        var creatorView = service.getRoomForMember(creator.getId(), id);
        var joinerView = service.getRoomForMember(joiner.getId(), id);
        assertThat(creatorView.participants()).extracting("displayName").containsOnly("Same Name");
        assertThat(creatorView.participants()).filteredOn("current", true).singleElement()
                .extracting("speaking", "listening").containsExactly("en", "ta");
        assertThat(creatorView.participants()).filteredOn("current", false).singleElement()
                .extracting("speaking", "listening").containsExactly("ta", "en");
        assertThat(joinerView.participants()).filteredOn("current", true).singleElement()
                .extracting("speaking", "listening").containsExactly("ta", "en");
        assertThat(joinerView.participants()).filteredOn("current", false).singleElement()
                .extracting("speaking", "listening").containsExactly("en", "ta");
        assertThat(creatorView.currentParticipant().participantId())
                .isNotEqualTo(joinerView.currentParticipant().participantId());
    }

    @Test
    void participantSetupPersistsNamesComplementaryLanguagesAndServerDerivedOwner() {
        UserAccount englishOwner = account("Account Owner");
        UserAccount tamilOwner = account("Tamil Account");
        UserAccount joiner = account("Joiner Account");

        UUID englishRoom = service.createRoomForSpeaker(englishOwner.getId(), "  Alice  ", " EN ",
                AiServiceProfile.STANDARD, true);
        UUID tamilRoom = service.createRoomForSpeaker(tamilOwner.getId(), "தமிழ் உரிமையாளர்", "ta",
                AiServiceProfile.STANDARD, true);
        assertThat(service.getRoomForMember(englishOwner.getId(), englishRoom).currentParticipant())
                .extracting("displayName", "speaking", "listening", "owner")
                .containsExactly("Alice", "en", "ta", true);
        assertThat(service.getRoomForMember(tamilOwner.getId(), tamilRoom).currentParticipant())
                .extracting("displayName", "speaking", "listening", "owner")
                .containsExactly("தமிழ் உரிமையாளர்", "ta", "en", true);

        String code = rooms.findById(englishRoom).orElseThrow().getJoinCode();
        var preview = service.previewJoin(joiner.getId(), code);
        assertThat(preview.ownerDisplayName()).isEqualTo("Alice");
        assertThat(preview.ownerSpeaking()).isEqualTo("en");
        assertThat(preview.participantSpeaking()).isEqualTo("ta");
        service.joinRoomWithSetup(joiner.getId(), code, "Alice");

        var ownerView = service.getRoomForMember(englishOwner.getId(), englishRoom);
        var joinerView = service.getRoomForMember(joiner.getId(), englishRoom);
        assertThat(ownerView.participants()).extracting("displayName").containsExactly("Alice", "Alice");
        assertThat(ownerView.participants()).filteredOn("owner", true).singleElement()
                .extracting("current", "speaking", "listening").containsExactly(true, "en", "ta");
        assertThat(joinerView.participants()).filteredOn("owner", true).singleElement()
                .extracting("current", "speaking", "listening").containsExactly(false, "en", "ta");
        assertThat(joinerView.currentParticipant())
                .extracting("displayName", "speaking", "listening", "owner")
                .containsExactly("Alice", "ta", "en", false);
        assertThat(service.joinRoomWithSetup(joiner.getId(), code, "Ignored duplicate name"))
                .isEqualTo(englishRoom);
        assertThat(service.getRoomForMember(joiner.getId(), englishRoom).currentParticipant().displayName())
                .isEqualTo("Alice");
    }

    @Test
    void displayNamesValidateUnicodeBoundariesEscapeMarkupAndLegacyRoomsFallback() throws Exception {
        UserAccount creator = account("Legacy Account Name");
        assertThatThrownBy(() -> service.createRoomForSpeaker(creator.getId(), "   ", "en",
                AiServiceProfile.STANDARD, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRoomForSpeaker(creator.getId(), "x".repeat(49), "en",
                AiServiceProfile.STANDARD, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRoomForSpeaker(creator.getId(), "Alex\u202Eowner", "en",
                AiServiceProfile.STANDARD, true)).isInstanceOf(IllegalArgumentException.class);

        String unicodeMarkupName = "<Alex & நண்பர்>";
        UUID named = service.createRoomForSpeaker(creator.getId(), unicodeMarkupName, "en",
                AiServiceProfile.STANDARD, true);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(named).getFirst()
                .getConversationDisplayName()).isEqualTo(unicodeMarkupName);
        String page = mvc.perform(get("/rooms/" + named).with(user(principal(creator))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("&lt;Alex &amp; நண்பர்&gt;").doesNotContain("<Alex & நண்பர்>");

        UUID legacy = service.createRoom(creator.getId(), "ta", "en");
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(legacy).getFirst()
                .getConversationDisplayName()).isNull();
        assertThat(service.getRoomForMember(creator.getId(), legacy).currentParticipant().displayName())
                .isEqualTo("Legacy Account Name");
    }

    @Test
    void invitePreviewAndSetupExposeOnlyAuthorizedConversationDetailsAndRecheckCapacity() throws Exception {
        UserAccount creator = account("Private Account Name");
        UserAccount joiner = account("Joiner Account");
        UserAccount third = account("Third Account");
        UUID id = service.createRoomForSpeaker(creator.getId(), "Room Host", "en",
                AiServiceProfile.STANDARD, true);
        String code = rooms.findById(id).orElseThrow().getJoinCode();

        String preview = mvc.perform(post("/rooms/join/preview").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("Join Conversation", "Room Host", "Owner speaks", "English",
                        "Your speaking language", "Tamil", "action=\"/rooms/join/setup\"")
                .doesNotContain(creator.getEmail(), creator.getId().toString(), "Private Account Name");

        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest").param("speaking", "ta"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms/" + id));
        String denied = mvc.perform(post("/rooms/join/preview").with(user(principal(third))).with(csrf())
                        .param("joinCode", code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(denied).contains("Invite unavailable. Check the code or ask for a new room.")
                .doesNotContain("Room Host", creator.getEmail(), creator.getId().toString());
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(2);
    }

    @Test
    void setupFormsRejectEmptyOversizedAndContradictoryClientLanguage() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                        .param("displayName", "").param("speaking", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Enter your display name for this conversation.")));
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                        .param("displayName", "x".repeat(49)).param("speaking", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Use 1 to 48 Unicode characters")));

        UUID id = service.createRoomForSpeaker(creator.getId(), "Owner", "en",
                AiServiceProfile.STANDARD, true);
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "")
                        .param("speaking", "en").param("owner", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Enter your display name for this conversation.")));
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest")
                        .param("speaking", "en").param("owner", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose the available language for this conversation.")));
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest")
                        .param("speaking", "fr"))
                .andExpect(status().isOk());
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(1);
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest")
                        .param("speaking", "ta"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms/" + id));
        assertThat(service.getRoomForMember(joiner.getId(), id).currentParticipant())
                .extracting("speaking", "listening", "owner").containsExactly("ta", "en", false);
    }

    @Test
    void tamilSpeakingOwnerRequiresEnglishSpeakingPartner() throws Exception {
        UserAccount creator = account("Tamil owner");
        UserAccount joiner = account("English guest");
        UUID id = service.createRoomForSpeaker(creator.getId(), "Owner", "ta",
                AiServiceProfile.STANDARD, true);
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        String preview = mvc.perform(post("/rooms/join/preview").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("Tamil — Used by conversation owner", "English — Available");
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest").param("speaking", "ta"))
                .andExpect(status().isOk());
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(1);
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Guest").param("speaking", "en"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms/" + id));
        assertThat(service.getRoomForMember(joiner.getId(), id).currentParticipant())
                .extracting("speaking", "listening").containsExactly("en", "ta");
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
                .param("displayName", "Creator")).andExpect(status().isForbidden());
        mvc.perform(post("/rooms/join").with(user(principal(joiner))).param("joinCode", code))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rooms/" + id + "/close").with(user(principal(creator))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                .param("joinCode", code).param("displayName", "Joiner")
                .param("speaking", "ta")
                .param("userId", creator.getId().toString()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms/" + id));
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id).get(1).getUser().getId())
                .isEqualTo(joiner.getId());
        mvc.perform(get("/ws/future").with(user(principal(joiner)))).andExpect(status().isForbidden());
    }

    @Test
    void webCreationValidationAndGenericJoinErrorAreSafe() throws Exception {
        UserAccount creator = account("Creator");
        mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("displayName", "Creator").param("speaking", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose English or Tamil.")));
        mvc.perform(post("/rooms/join").with(user(principal(creator))).with(csrf())
                .param("joinCode", "missing"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Invite unavailable. Check the code or ask for a new room.")));
        assertThat(service.listRoomsForMember(creator.getId())).isEmpty();
    }

    @Test
    void browserCanCreateJoinAndExplicitlyCloseUsingPrincipalIdentity() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UserAccount outsider = account("Outsider");
        String redirect = mvc.perform(post("/rooms").with(user(principal(creator))).with(csrf())
                .param("displayName", "Room Creator").param("speaking", "en")
                .param("userId", outsider.getId().toString()))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        UUID id = UUID.fromString(redirect.substring("/rooms/".length()));
        assertThat(rooms.findById(id).orElseThrow().getCreatedBy().getId()).isEqualTo(creator.getId());
        String code = rooms.findById(id).orElseThrow().getJoinCode();
        mvc.perform(get(redirect).with(user(principal(creator))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Waiting for a guest")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("and you will receive")));
        mvc.perform(post("/rooms/join").with(user(principal(joiner))).with(csrf()).param("joinCode", code))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Join Conversation")));
        mvc.perform(post("/rooms/join/setup").with(user(principal(joiner))).with(csrf())
                        .param("joinCode", code).param("displayName", "Room Guest")
                        .param("speaking", "ta"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(redirect));
        mvc.perform(get(redirect).with(user(principal(joiner))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No conversation activity yet")));
        mvc.perform(post(redirect + "/close").with(user(principal(outsider))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(redirect + "/close").with(user(principal(joiner))).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl(redirect));
        assertThat(rooms.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        mvc.perform(get(redirect).with(user(principal(creator))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cannot be reopened")));
    }

    @Test
    void removingClosedRoomOnlyHidesItForRequestingMember() {
        UserAccount creator = account("Creator");
        UserAccount guest = account("Guest");
        UserAccount outsider = account("Outsider");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        assertThatThrownBy(() -> service.removeClosedRoom(creator.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
        service.joinRoom(guest.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        assertThatThrownBy(() -> service.removeClosedRoom(guest.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
        service.closeRoom(creator.getId(), id);
        assertThatThrownBy(() -> service.removeClosedRoom(outsider.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
        service.removeClosedRoom(creator.getId(), id);
        assertThat(service.listRoomsForMember(creator.getId())).noneMatch(view -> view.id().equals(id));
        assertThat(service.listRoomsForMember(guest.getId())).anyMatch(view -> view.id().equals(id));
        assertThat(rooms.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(2);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id).getFirst().getRemovedAt()).isNotNull();
        assertThatThrownBy(() -> service.getRoomForMember(creator.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.removeClosedRoom(creator.getId(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void closedRoomRemovalPostRequiresMemberCsrfAndConfirmationPage() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount guest = account("Guest");
        UserAccount outsider = account("Outsider");
        UUID id = service.createRoom(creator.getId(), "en", "ta");
        service.joinRoom(guest.getId(), rooms.findById(id).orElseThrow().getJoinCode());
        String path = "/rooms/" + id + "/remove";
        mvc.perform(get(path).with(user(principal(creator)))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(path).with(user(principal(creator))).with(csrf()))
                .andExpect(status().isConflict());
        service.closeRoom(creator.getId(), id);
        mvc.perform(get("/rooms/" + id).with(user(principal(creator))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Confirm removal")));
        mvc.perform(get("/rooms").with(user(principal(creator))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove room")));
        mvc.perform(post(path).with(user(principal(creator))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(user(principal(outsider))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(user(principal(creator))).with(csrf())
                .param("userId", guest.getId().toString()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms"));
        mvc.perform(get("/rooms/" + id).with(user(principal(creator))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/rooms/" + id).with(user(principal(guest))))
                .andExpect(status().isOk());
    }
}
