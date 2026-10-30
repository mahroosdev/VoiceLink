package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.UUID;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.ProviderFailure;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "voicelink.text.max-invalidations-per-user-per-minute=2",
        "voicelink.text.max-invalidations-per-instance-per-minute=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TextTranslationFlowTests {
    @Autowired MockMvc mvc;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired UserAccountRepository accounts;
    @Autowired ObjectMapper json;
    @MockitoBean(name = "geminiTranslationProvider") TranslationProvider standard;
    @MockitoBean RoomLiveHub hub;

    private UserAccount account() {
        return accounts.save(new UserAccount("Text tester", UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    private AccountPrincipal principal(UserAccount user) {
        return new AccountPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), true);
    }

    private UUID active(UserAccount first, UserAccount second) {
        UUID id = rooms.createRoom(first.getId(), "en", "ta");
        rooms.joinRoom(second.getId(), roomRepository.findById(id).orElseThrow().getJoinCode());
        return id;
    }

    private MockHttpServletRequestBuilder review(UUID roomId, String text, long revision,
            UserAccount user, MockHttpSession session) throws Exception {
        return post("/api/rooms/{roomId}/text/reviews", roomId).session(session)
                .with(user(principal(user))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Input(text, revision, false, null)));
    }

    @Test void reviewRequiresAuthenticationCsrfAndMembership() throws Exception {
        UserAccount creator = account(), joiner = account(), outsider = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/rooms/{roomId}/text/reviews", roomId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(review(roomId, "Hello", 1, creator, session)).andExpect(status().isForbidden());
        mvc.perform(review(roomId, "Hello", 1, outsider, session).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void reviewIsPrivateAndSendFinalizesOnceWithServerDirection() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        when(standard.translate(any())).thenReturn(new TranslationProvider.Result(
                "Hello", "GEMINI", "gemini-3.1-flash-lite", 1));
        String reviewBody = mvc.perform(review(roomId, "வணக்கம்", 4, joiner, session).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var result = json.readTree(reviewBody);
        assertThat(result.get("sourceLanguage").asText()).isEqualTo("ta");
        assertThat(result.get("targetLanguage").asText()).isEqualTo("en");
        verify(hub, org.mockito.Mockito.never()).publishTurn(any(), any(), any(), any(), any(), any());
        UUID reviewId = UUID.fromString(result.get("reviewId").asText());
        String sendBody = json.writeValueAsString(new Send(reviewId, "வணக்கம்", 4));
        MockHttpServletRequestBuilder request = post("/api/rooms/{roomId}/text/send", roomId)
                .session(session).with(user(principal(joiner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(sendBody);
        var first = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).get("duplicate").asBoolean()).isFalse();
        var retry = mvc.perform(post("/api/rooms/{roomId}/text/send", roomId).session(session)
                        .with(user(principal(joiner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(retry).get("duplicate").asBoolean()).isTrue();
        verify(hub).publishTurn(org.mockito.ArgumentMatchers.eq(roomId),
                org.mockito.ArgumentMatchers.eq(joiner.getId()), any(), any(),
                org.mockito.ArgumentMatchers.eq("TEXT_TRANSLATION_SENT"), any());
    }

    @Test void invalidTextAndClosedRoomAreRejected() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(review(roomId, " ", 1, creator, session).with(csrf()))
                .andExpect(status().isBadRequest());
        rooms.closeRoom(creator.getId(), roomId);
        mvc.perform(review(roomId, "Hello", 1, creator, session).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test void liveSharingRequiresTheCurrentAuthenticatedRoomSocketAndCanBeStopped() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/rooms/{roomId}/text/shares", roomId).session(session)
                        .with(user(principal(creator))).with(csrf()))
                .andExpect(status().isConflict());
        when(hub.hasCurrentConnection(roomId, creator.getId(), session.getId())).thenReturn(true);
        String body = mvc.perform(post("/api/rooms/{roomId}/text/shares", roomId).session(session)
                        .with(user(principal(creator))).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID epoch = UUID.fromString(json.readTree(body).get("sharingEpoch").asText());
        mvc.perform(delete("/api/rooms/{roomId}/text/shares/{epoch}", roomId, epoch).session(session)
                        .with(user(principal(creator))).with(csrf()))
                .andExpect(status().isNoContent());
        verify(hub, org.mockito.Mockito.never()).publishDraft(any());
    }

    @Test void liveInvalidationRequiresAuthenticationCsrfMembershipSessionAndValidEpoch() throws Exception {
        UserAccount creator = account(), joiner = account(), outsider = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        when(hub.hasCurrentConnection(roomId, creator.getId(), session.getId())).thenReturn(true);
        String share = mvc.perform(post("/api/rooms/{roomId}/text/shares", roomId).session(session)
                        .with(user(principal(creator))).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID epoch = UUID.fromString(json.readTree(share).get("sharingEpoch").asText());
        String body = json.writeValueAsString(new Invalidate(2));
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern("/login*"));
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(session).with(user(principal(creator)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(new MockHttpSession()).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(session).with(user(principal(outsider))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, UUID.randomUUID())
                        .session(session).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(session).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(session).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Invalidate(3))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, epoch)
                        .session(session).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Invalidate(4))))
                .andExpect(status().isTooManyRequests());
        org.mockito.Mockito.verify(standard, org.mockito.Mockito.never()).translate(any());
    }

    @Test void oversizedReviewBodyIsRejectedBeforeProviderWork() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        mvc.perform(review(roomId, "x".repeat(9_000), 1, creator, new MockHttpSession()).with(csrf()))
                .andExpect(status().is(413));
        org.mockito.Mockito.verify(standard, org.mockito.Mockito.never()).translate(any());
    }

    @Test void oversizedInvalidationBodyIsRejected() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        MockHttpSession session = new MockHttpSession();
        String oversized = "{\"revision\":1}" + " ".repeat(9_000);
        mvc.perform(post("/api/rooms/{roomId}/text/shares/{epoch}/invalidate", roomId, UUID.randomUUID())
                        .session(session).with(user(principal(creator))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().is(413));
    }

    @Test void timeoutReturnsOnlySafeClassificationAndKeepsPrivateContentOutOfTheError() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        when(standard.translate(any())).thenThrow(ProviderFailure.localTimeout());
        mvc.perform(review(roomId, "SENSITIVE_CANARY_MESSAGE", 9, creator,
                        new MockHttpSession()).with(csrf()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("TIMED_OUT"))
                .andExpect(jsonPath("$.error").value(
                        "Translation took too long. Your message remains here; try again."))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("SENSITIVE_CANARY_MESSAGE"));
    }

    private record Input(String sourceText, long revision, boolean romanizedTamil, UUID sharingEpoch) {}
    private record Send(UUID reviewId, String sourceText, long revision) {}
    private record Invalidate(long revision) {}
}
