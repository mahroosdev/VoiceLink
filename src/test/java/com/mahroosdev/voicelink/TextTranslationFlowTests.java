package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
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

@SpringBootTest
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
                .content(json.writeValueAsString(new Input(text, revision)));
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

    @Test void oversizedReviewBodyIsRejectedBeforeProviderWork() throws Exception {
        UserAccount creator = account(), joiner = account();
        UUID roomId = active(creator, joiner);
        mvc.perform(review(roomId, "x".repeat(9_000), 1, creator, new MockHttpSession()).with(csrf()))
                .andExpect(status().is(413));
        org.mockito.Mockito.verify(standard, org.mockito.Mockito.never()).translate(any());
    }

    private record Input(String sourceText, long revision) {}
    private record Send(UUID reviewId, String sourceText, long revision) {}
}
