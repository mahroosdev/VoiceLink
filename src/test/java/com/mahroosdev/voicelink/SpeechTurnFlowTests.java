package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.conversation.SpeechTurnService;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpeechTurnFlowTests {
    @Autowired MockMvc mvc;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired UserAccountRepository accounts;
    @Autowired SpeechTurnService turns;
    @Autowired ObjectMapper json;
    @Autowired RoomGlossaryService glossary;
    @MockitoBean SpeechToTextProvider stt;
    @MockitoBean TranslationProvider translation;
    @MockitoBean TextToSpeechProvider tts;
    @MockitoBean RoomLiveHub hub;

    private UserAccount account(String label) {
        return accounts.save(new UserAccount(label, UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    private UserDetails principal(UserAccount account) {
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash(), true);
    }

    private UUID activeRoom(UserAccount creator, UserAccount joiner, String source) {
        UUID id = rooms.createRoom(creator.getId(), source, "en".equals(source) ? "ta" : "en");
        rooms.joinRoom(joiner.getId(), roomRepository.findById(id).orElseThrow().getJoinCode());
        return id;
    }

    private static byte[] webm() {
        byte[] data = new byte[64];
        data[0] = 0x1a; data[1] = 0x45; data[2] = (byte) 0xdf; data[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, data, 8, marker.length);
        return data;
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request(
            UUID roomId, UUID clientId, byte[] bytes, String mediaType) {
        return multipart("/api/rooms/{roomId}/turns", roomId)
                .file(new MockMultipartFile("audio", "turn.webm", mediaType, bytes))
                .param("clientRequestId", clientId.toString()).param("durationMillis", "1000");
    }

    private void successProviders(String source, String target) {
        String transcript = "en".equals(source) ? "Hello Java" : "வணக்கம் Java";
        String translated = "ta".equals(target) ? "வணக்கம் Java" : "Hello Java";
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result(transcript, "GROQ",
                "whisper-large-v3", 10));
        when(translation.translate(any())).thenReturn(new TranslationProvider.Result(translated,
                "GEMINI", "gemini-3.1-flash-lite", 10));
        when(tts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(
                new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 10));
    }

    private SpeechTurnService.Snapshot await(UUID userId, UUID roomId, String status) throws Exception {
        for (int i = 0; i < 150; i++) {
            var list = turns.recent(userId, roomId);
            if (!list.isEmpty() && status.equals(list.getLast().status())) return list.getLast();
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for turn status " + status);
    }

    @Test void uploadRequiresAuthenticationCsrfMembershipAndActiveRoom() throws Exception {
        UserAccount a = account("A"), b = account("B"), outsider = account("Outsider");
        UUID waiting = rooms.createRoom(a.getId(), "en", "ta");
        UUID requestId = UUID.randomUUID();
        mvc.perform(request(waiting, requestId, webm(), "audio/webm;codecs=opus").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(request(waiting, requestId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(a)))).andExpect(status().isForbidden());
        mvc.perform(request(waiting, requestId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(outsider))).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(request(waiting, requestId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(a))).with(csrf())).andExpect(status().isConflict());
        rooms.joinRoom(b.getId(), roomRepository.findById(waiting).orElseThrow().getJoinCode());
        rooms.closeRoom(a.getId(), waiting);
        mvc.perform(request(waiting, requestId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(a))).with(csrf())).andExpect(status().isConflict());
    }

    @Test void uploadValidatesAudioAndDoesNotAcceptBrowserLanguageOrIdentity() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID roomId = activeRoom(a, b, "ta");
        mvc.perform(request(roomId, UUID.randomUUID(), new byte[0], "audio/webm;codecs=opus")
                .with(user(principal(a))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(request(roomId, UUID.randomUUID(), webm(), "audio/wav")
                .with(user(principal(a))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(request(roomId, UUID.randomUUID(), new byte[1024 * 1024 + 1],
                "audio/webm;codecs=opus").with(user(principal(a))).with(csrf()))
                .andExpect(status().is4xxClientError());
        successProviders("ta", "en");
        var accepted = mvc.perform(request(roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus")
                .param("userId", b.getId().toString()).param("sourceLanguage", "en")
                .param("targetLanguage", "ta").with(user(principal(a))).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(json.readTree(accepted.getResponse().getContentAsString()).path("profile").asText())
                .isEqualTo("STANDARD");
        var translated = await(a.getId(), roomId, "READY");
        assertThat(translated.transcript()).isEqualTo("வணக்கம் Java");
        assertThat(translated.translatedText()).isEqualTo("Hello Java");
        verify(stt).transcribe(org.mockito.ArgumentMatchers.argThat(input ->
                input.sourceLanguage().equals("ta")));
        verify(translation).translate(org.mockito.ArgumentMatchers.argThat(input ->
                input.sourceLanguage().equals("ta") && input.targetLanguage().equals("en")));
        verify(tts).synthesize(org.mockito.ArgumentMatchers.argThat(input ->
                input.targetLanguage().equals("en")));
    }

    @Test void successIsIdempotentAndAudioIsMemberOnly() throws Exception {
        UserAccount a = account("A"), b = account("B"), outsider = account("Outsider");
        UUID roomId = activeRoom(a, b, "en");
        successProviders("en", "ta");
        UUID clientId = UUID.randomUUID();
        var first = mvc.perform(request(roomId, clientId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(a))).with(csrf())).andExpect(status().isAccepted()).andReturn();
        var second = mvc.perform(request(roomId, clientId, webm(), "audio/webm;codecs=opus")
                .with(user(principal(a))).with(csrf())).andExpect(status().isAccepted()).andReturn();
        var firstJson = json.readTree(first.getResponse().getContentAsString());
        var secondJson = json.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.path("turnId").asText()).isEqualTo(firstJson.path("turnId").asText());
        assertThat(secondJson.path("duplicate").booleanValue()).isTrue();
        var snapshot = await(a.getId(), roomId, "READY");
        assertThat(snapshot.transcript()).isEqualTo("Hello Java");
        assertThat(snapshot.translatedText()).isEqualTo("வணக்கம் Java");
        verify(stt).transcribe(any());
        verify(translation).translate(any());
        verify(tts).synthesize(any());
        verify(hub, timeout(2000)).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("AUDIO_READY"), any());
        var eventOrder = inOrder(hub);
        eventOrder.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("TURN_ACCEPTED"), any());
        eventOrder.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("TRANSCRIPT_READY"), any());
        eventOrder.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("TRANSLATION_READY"), any());
        eventOrder.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("AUDIO_READY"), any());
        String path = "/api/rooms/" + roomId + "/turns/" + snapshot.turnId() + "/audio";
        mvc.perform(get(path).with(user(principal(b)))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("audio/wav"));
        mvc.perform(get(path).with(user(principal(outsider)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/rooms/{roomId}/turns/{turnId}/audio", UUID.randomUUID(), snapshot.turnId())
                .with(user(principal(b)))).andExpect(status().isNotFound());
        verify(hub, atLeastOnce()).publishTurn(eq(roomId), eq(a.getId()), any(), eq(snapshot.turnId()),
                eq("AUDIO_READY"), any());
    }

    @Test void translationAndTtsFailureKeepEarlierCaptions() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID roomId = activeRoom(a, b, "en");
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result("Hello", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.QUOTA));
        turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var failedTranslation = await(a.getId(), roomId, "FAILED");
        assertThat(failedTranslation.transcript()).isEqualTo("Hello");
        assertThat(failedTranslation.translatedText()).isNull();
        assertThat(failedTranslation.failedStage()).isEqualTo("TRANSLATION");
        org.mockito.Mockito.doReturn(new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1))
                .when(translation).translate(any());
        when(tts.synthesize(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.TIMEOUT));
        turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var failedTts = await(a.getId(), roomId, "FAILED");
        assertThat(failedTts.transcript()).isEqualTo("Hello");
        assertThat(failedTts.translatedText()).isEqualTo("வணக்கம்");
        assertThat(failedTts.audioAvailable()).isFalse();
        assertThat(failedTts.failedStage()).isEqualTo("TTS");
    }

    @Test void sttFailureDoesNotInventTranscriptOrCallLaterProviders() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID roomId = activeRoom(a, b, "en");
        when(stt.transcribe(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.CONFIGURATION));
        turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var failed = await(a.getId(), roomId, "FAILED");
        assertThat(failed.failedStage()).isEqualTo("STT");
        assertThat(failed.transcript()).isNull();
        verify(translation, never()).translate(any());
        verify(tts, never()).synthesize(any());
    }

    @Test void closingRoomDiscardsLateProviderResult() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID roomId = activeRoom(a, b, "en");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(stt.transcribe(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new SpeechToTextProvider.Result("late", "GROQ", "whisper-large-v3", 1);
        });
        turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        rooms.closeRoom(a.getId(), roomId);
        release.countDown();
        Thread.sleep(100);
        verify(translation, never()).translate(any());
        verify(hub, never()).publishTurn(eq(roomId), eq(a.getId()), any(), any(),
                eq("TRANSCRIPT_READY"), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> turns.recent(a.getId(), roomId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void failedEarlierTurnReleasesLaterTurnInAcceptanceOrder() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID roomId = activeRoom(a, b, "en");
        when(stt.transcribe(any()))
                .thenThrow(new ProviderFailure(ProviderFailure.Code.UNAVAILABLE))
                .thenReturn(new SpeechToTextProvider.Result("second", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenReturn(new TranslationProvider.Result(
                "இரண்டாவது", "GEMINI", "gemini-3.1-flash-lite", 1));
        when(tts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(
                new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));
        var first = turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var second = turns.accept(a.getId(), roomId, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        assertThat(first.turnIndex()).isEqualTo(1);
        assertThat(second.turnIndex()).isEqualTo(2);
        await(a.getId(), roomId, "READY");
        verify(hub, timeout(2000)).publishTurn(eq(roomId), eq(a.getId()), any(), eq(second.turnId()),
                eq("AUDIO_READY"), any());
        var order = org.mockito.Mockito.inOrder(hub);
        order.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(first.turnId()),
                eq("TURN_FAILED"), any());
        order.verify(hub).publishTurn(eq(roomId), eq(a.getId()), any(), eq(second.turnId()),
                eq("AUDIO_READY"), any());
    }

    @Test void serialTurnsSendOnlyPriorTranslatedSourcesAndApplicableGlossary() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID room = activeRoom(a, b, "en");
        glossary.create(a.getId(), room, new RoomGlossaryService.Change("en", "ta", "REST API", "ரெஸ்ட் API"));
        glossary.create(b.getId(), room, new RoomGlossaryService.Change("ta", "en", "Java", "Java"));
        when(stt.transcribe(any())).thenReturn(
                new SpeechToTextProvider.Result("The REST API works", "GROQ", "whisper-large-v3", 1),
                new SpeechToTextProvider.Result("Java நல்லது", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenReturn(
                new TranslationProvider.Result("REST API வேலை செய்கிறது", "GEMINI", "gemini-3.1-flash-lite", 1),
                new TranslationProvider.Result("Java is good", "GEMINI", "gemini-3.1-flash-lite", 1));
        when(tts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(
                new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));
        turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        await(a.getId(), room, "READY");
        turns.accept(b.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        await(b.getId(), room, "READY");
        var captor = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(translation, org.mockito.Mockito.times(2)).translate(captor.capture());
        var requests = captor.getAllValues();
        assertThat(requests.get(0).recentContext()).isEmpty();
        assertThat(requests.get(0).glossary()).extracting(item -> item.sourceTerm())
                .containsExactly("REST API");
        assertThat(requests.get(1).recentContext()).hasSize(1);
        assertThat(requests.get(1).recentContext().getFirst().sourceTranscript())
                .isEqualTo("The REST API works");
        assertThat(requests.get(1).recentContext().getFirst().turnIndex()).isEqualTo(1);
        assertThat(requests.get(1).glossary()).extracting(item -> item.sourceTerm()).containsExactly("Java");
        rooms.closeRoom(a.getId(), room);
        assertThatThrownBy(() -> turns.recent(a.getId(), room))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void failedStagesDoNotContaminateLaterContextButTtsFailureRemainsEligible() throws Exception {
        UserAccount a = account("A"), b = account("B");
        UUID room = activeRoom(a, b, "en");
        when(stt.transcribe(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.UNAVAILABLE))
                .thenReturn(new SpeechToTextProvider.Result("translation fails", "GROQ", "whisper-large-v3", 1),
                        new SpeechToTextProvider.Result("translated before TTS", "GROQ", "whisper-large-v3", 1),
                        new SpeechToTextProvider.Result("fourth", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.QUOTA))
                .thenReturn(new TranslationProvider.Result("மூன்றாவது", "GEMINI", "gemini-3.1-flash-lite", 1),
                        new TranslationProvider.Result("நான்காவது", "GEMINI", "gemini-3.1-flash-lite", 1));
        when(tts.synthesize(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.TIMEOUT))
                .thenReturn(new TextToSpeechProvider.Result(new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));
        for (int i = 0; i < 4; i++) {
            turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
            await(a.getId(), room, i == 3 ? "READY" : "FAILED");
        }
        var captor = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(translation, org.mockito.Mockito.times(3)).translate(captor.capture());
        assertThat(captor.getAllValues().get(2).recentContext())
                .extracting(item -> item.sourceTranscript()).containsExactly("translated before TTS");
    }
}
