package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.PremiumProviderSettings;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.conversation.SpeechTurnService;
import com.mahroosdev.voicelink.conversation.SharedConversationContext;
import com.mahroosdev.voicelink.conversation.TextTranslationService;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"voicelink.premium.enabled=true", "voicelink.premium.gemini-key=synthetic-premium-key"})
class PremiumSpeechTurnFlowTests {
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired UserAccountRepository accounts;
    @Autowired SpeechTurnService turns;
    @Autowired RoomGlossaryService glossary;
    @Autowired TextTranslationService text;
    @Autowired SharedConversationContext context;
    @Autowired PremiumProviderSettings premiumSettings;
    @Autowired MockMvc mvc;
    @MockitoBean SpeechToTextProvider stt;
    @MockitoBean(name = "geminiTranslationProvider") TranslationProvider standardTranslation;
    @MockitoBean(name = "geminiTextToSpeechProvider") TextToSpeechProvider standardTts;
    @MockitoBean(name = "premiumTranslationProvider") TranslationProvider premiumTranslation;
    @MockitoBean(name = "premiumTextToSpeechProvider") TextToSpeechProvider premiumTts;

    private UserAccount account() {
        return accounts.save(new UserAccount("Profile test", UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    private UUID activePremium(UserAccount a, UserAccount b) {
        UUID id = rooms.createRoom(a.getId(), "en", "ta", AiServiceProfile.PREMIUM);
        rooms.joinRoom(b.getId(), roomRepository.findById(id).orElseThrow().getJoinCode());
        assertThat(rooms.getRoomForMember(a.getId(), id).profile()).isEqualTo(AiServiceProfile.PREMIUM);
        assertThat(rooms.getRoomForMember(b.getId(), id).profile()).isEqualTo(AiServiceProfile.PREMIUM);
        return id;
    }

    private static byte[] webm() {
        byte[] data = new byte[64];
        data[0] = 0x1a; data[1] = 0x45; data[2] = (byte) 0xdf; data[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, data, 8, marker.length);
        return data;
    }

    @Test void joinerSeesStoredPremiumProfileAndNoCredential() throws Exception {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        mvc.perform(get("/rooms/{id}", room).with(user(new AccountPrincipal(
                b.getId(), b.getEmail(), b.getPasswordHash(), true))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Premium")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("synthetic-premium-key"))));
    }

    @Test void premiumCreationPersistsAuthoritativeCostSaverAndRejectsTampering() throws Exception {
        UserAccount owner = account();
        AccountPrincipal principal = new AccountPrincipal(owner.getId(), owner.getEmail(),
                owner.getPasswordHash(), true);
        mvc.perform(post("/rooms").with(user(principal)).with(csrf())
                        .param("displayName", "Premium owner").param("speaking", "en")
                        .param("profile", "PREMIUM").param("premiumCostSaver", "OFF"))
                .andExpect(status().is3xxRedirection());
        var created = rooms.listRoomsForMember(owner.getId()).getFirst();
        assertThat(created.profile()).isEqualTo(AiServiceProfile.PREMIUM);
        assertThat(created.premiumCostSaverEnabled()).isFalse();
        mvc.perform(get("/rooms/{id}", created.id()).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("API Cost Saver OFF")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-live-text-debounce-ms=\"800\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("synthetic-premium-key"))));

        int before = rooms.listRoomsForMember(owner.getId()).size();
        mvc.perform(post("/rooms").with(user(principal)).with(csrf())
                        .param("displayName", "Premium owner").param("speaking", "en")
                        .param("profile", "PREMIUM").param("premiumCostSaver", "FORGED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose API Cost Saver ON or OFF.")));
        assertThat(rooms.listRoomsForMember(owner.getId())).hasSize(before);
    }

    @Test void premiumTotalTextUsesPremiumTranslationWithSharedContextAndGlossary() {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        glossary.create(a.getId(), room, new RoomGlossaryService.Change("en", "ta", "REST API", "REST API"));
        context.finalized(room, "ta", "en", "முந்தைய செய்தி");
        when(premiumTranslation.translate(any())).thenReturn(new TranslationProvider.Result(
                "REST API தயாராக உள்ளது", "GEMINI", "gemini-3.8-flash", 1));
        var review = text.review(a.getId(), "premium-session", room, "The REST API is ready", 1);
        var sent = text.send(a.getId(), "premium-session", room, review.reviewId(),
                "The REST API is ready", 1);
        assertThat(review.profile()).isEqualTo("PREMIUM");
        assertThat(sent.duplicate()).isFalse();
        var request = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(premiumTranslation).translate(request.capture());
        assertThat(request.getValue().recentContext()).hasSize(1);
        assertThat(request.getValue().glossary()).extracting(TranslationProvider.GlossaryTerm::sourceTerm)
                .containsExactly("REST API");
        verify(standardTranslation, never()).translate(any());
        verify(standardTts, never()).synthesize(any());
    }

    @Test void premiumFinishAndSendBuildsPrivateReviewWithoutAbandonedTts() throws Exception {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result(
                "Hello", "GROQ", "whisper-large-v3", 1));
        when(premiumTranslation.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம்", "GEMINI", "gemini-3.8-flash", 1));
        var accepted = turns.accept(a.getId(), "premium-session", room, UUID.randomUUID(),
                SpeechTurnService.Workflow.FINISH_AND_SEND, webm(), "audio/webm;codecs=opus", 1000);
        SpeechTurnService.Review review = null;
        for (int i = 0; i < 150; i++) {
            review = turns.review(a.getId(), "premium-session", room, accepted.turnId());
            if ("READY_FOR_REVIEW".equals(review.status())) break;
            Thread.sleep(20);
        }
        assertThat(review).isNotNull();
        assertThat(review.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(review.profile()).isEqualTo("PREMIUM");
        verify(premiumTranslation).translate(any());
        verify(premiumTts, never()).synthesize(any());
        turns.cancel(a.getId(), "premium-session", room, accepted.turnId());
    }

    private SpeechTurnService.Snapshot await(UUID userId, UUID roomId, String state) throws Exception {
        for (int i = 0; i < 150; i++) {
            var recent = turns.recent(userId, roomId);
            if (!recent.isEmpty() && state.equals(recent.getLast().status())) return recent.getLast();
            Thread.sleep(20);
        }
        throw new AssertionError("Turn did not reach " + state);
    }

    @Test void premiumUsesSharedGroqAndOnlyPremiumGeminiWithContextGlossaryAndOrdering() throws Exception {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        glossary.create(a.getId(), room, new RoomGlossaryService.Change("en", "ta", "REST API", "REST API"));
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result("The REST API works", "GROQ", "whisper-large-v3", 1),
                new SpeechToTextProvider.Result("The REST API works again", "GROQ", "whisper-large-v3", 1));
        when(premiumTranslation.translate(any())).thenReturn(new TranslationProvider.Result("REST API வேலை செய்கிறது", "GEMINI", "gemini-3.8-flash", 1));
        when(premiumTts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));
        var first = turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        assertThat(first.profile()).isEqualTo("PREMIUM");
        await(a.getId(), room, "READY");
        var second = turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        await(a.getId(), room, "READY");
        assertThat(second.turnIndex()).isEqualTo(first.turnIndex() + 1);
        var captor = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(premiumTranslation, org.mockito.Mockito.times(2)).translate(captor.capture());
        assertThat(captor.getAllValues().get(0).glossary()).extracting(TranslationProvider.GlossaryTerm::sourceTerm)
                .containsExactly("REST API");
        assertThat(captor.getAllValues().get(1).recentContext()).hasSize(1);
        verify(stt, org.mockito.Mockito.times(2)).transcribe(any());
        verify(premiumTts, org.mockito.Mockito.times(2)).synthesize(any());
        verify(standardTranslation, never()).translate(any());
        verify(standardTts, never()).synthesize(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProviderFailure.Code.class, names = {"CONFIGURATION", "QUOTA", "TIMEOUT"})
    void premiumTranslationFailureKeepsTranscriptAndNeverFallsBack(ProviderFailure.Code failure) throws Exception {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result("Hello", "GROQ", "whisper-large-v3", 1));
        when(premiumTranslation.translate(any())).thenThrow(new ProviderFailure(failure));
        turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var failed = await(a.getId(), room, "FAILED");
        assertThat(failed.transcript()).isEqualTo("Hello");
        assertThat(failed.translatedText()).isNull();
        assertThat(failed.failedStage()).isEqualTo("TRANSLATION");
        verify(standardTranslation, never()).translate(any());
        verify(standardTts, never()).synthesize(any());
        verify(premiumTts, never()).synthesize(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProviderFailure.Code.class, names = {"CONFIGURATION", "QUOTA", "TIMEOUT"})
    void premiumTtsFailureKeepsTranscriptAndTranslation(ProviderFailure.Code failure) throws Exception {
        UserAccount a = account(), b = account();
        UUID room = activePremium(a, b);
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result("Hello", "GROQ", "whisper-large-v3", 1));
        when(premiumTranslation.translate(any())).thenReturn(new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.8-flash", 1));
        when(premiumTts.synthesize(any())).thenThrow(new ProviderFailure(failure));
        turns.accept(a.getId(), room, UUID.randomUUID(), webm(), "audio/webm;codecs=opus", 1000);
        var failed = await(a.getId(), room, "FAILED");
        assertThat(failed.transcript()).isEqualTo("Hello");
        assertThat(failed.translatedText()).isEqualTo("வணக்கம்");
        assertThat(failed.failedStage()).isEqualTo("TTS");
        verify(standardTranslation, never()).translate(any());
        verify(standardTts, never()).synthesize(any());
    }
}
