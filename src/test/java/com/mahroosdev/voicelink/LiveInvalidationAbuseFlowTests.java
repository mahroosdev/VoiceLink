package com.mahroosdev.voicelink;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "voicelink.abuse.live-invalidation-per-account-per-minute=2",
        "voicelink.abuse.live-invalidation-per-instance-per-minute=100",
        "voicelink.text.max-invalidations-per-user-per-minute=20",
        "voicelink.text.max-invalidations-per-instance-per-minute=40"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveInvalidationAbuseFlowTests {
    @Autowired MockMvc mvc;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRows;
    @Autowired UserAccountRepository accounts;
    @Autowired ObjectMapper json;
    @MockitoBean RoomLiveHub hub;

    @Test void staleAndInvalidEpochRequestsConsumeAuthenticatedRequestQuotaAfterCsrf() throws Exception {
        UserAccount owner = account();
        UserAccount partner = account();
        UUID roomId = activeRoom(owner, partner);
        MockHttpSession session = new MockHttpSession();
        AccountPrincipal principal = principal(owner);
        whenCurrentSocket(roomId, owner, session);
        UUID validEpoch = startSharing(roomId, principal, session);
        UUID invalidEpoch = UUID.randomUUID();

        mvc.perform(invalidate(roomId, invalidEpoch, 1, principal, session))
                .andExpect(status().isForbidden());
        mvc.perform(invalidate(roomId, invalidEpoch, 1, principal, session).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(invalidate(roomId, invalidEpoch, 2, principal, session).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(invalidate(roomId, invalidEpoch, 3, principal, session).with(csrf()))
                .andExpect(status().isTooManyRequests());

        // The authenticated request quota is account-scoped and applies before epoch validation.
        mvc.perform(invalidate(roomId, validEpoch, 1, principal, session).with(csrf()))
                .andExpect(status().isTooManyRequests());
    }

    @Test void validInvalidationsWorkUntilAccountQuotaIsExhausted() throws Exception {
        UserAccount owner = account();
        UserAccount partner = account();
        UUID roomId = activeRoom(owner, partner);
        MockHttpSession session = new MockHttpSession();
        AccountPrincipal principal = principal(owner);
        whenCurrentSocket(roomId, owner, session);
        UUID epoch = startSharing(roomId, principal, session);

        mvc.perform(invalidate(roomId, epoch, 1, principal, session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(invalidate(roomId, epoch, 1, principal, session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(invalidate(roomId, epoch, 1, principal, session).with(csrf()))
                .andExpect(status().isTooManyRequests());
    }

    private UserAccount account() {
        return accounts.save(new UserAccount("Limiter test", UUID.randomUUID() + "@example.test", "test-hash"));
    }

    private UUID activeRoom(UserAccount owner, UserAccount partner) {
        UUID room = rooms.createRoom(owner.getId(), "en", "ta");
        rooms.joinRoom(partner.getId(), roomRows.findById(room).orElseThrow().getJoinCode());
        return room;
    }

    private UUID startSharing(UUID roomId, AccountPrincipal principal, MockHttpSession session) throws Exception {
        String response = mvc.perform(post("/api/rooms/{roomId}/text/shares", roomId).session(session)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("sharingEpoch").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invalidate(
            UUID roomId, UUID epoch, long revision, AccountPrincipal principal, MockHttpSession session)
            throws Exception {
        return post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                .session(session).with(user(principal)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Invalidate(revision)));
    }

    private void whenCurrentSocket(UUID roomId, UserAccount owner, MockHttpSession session) {
        org.mockito.Mockito.when(hub.hasCurrentConnection(roomId, owner.getId(), session.getId())).thenReturn(true);
    }

    private static AccountPrincipal principal(UserAccount account) {
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash(), true);
    }

    private record Invalidate(long revision) {}
}
