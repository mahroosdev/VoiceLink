package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.auth.PasswordResetService;
import com.mahroosdev.voicelink.auth.AccountDeletionService;
import com.mahroosdev.voicelink.auth.TestPasswordResetNotifier;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.conversation.TextSharingState;
import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"voicelink.standard.free-tier-confirmed=false",
                "voicelink.password-reset.enabled=true",
                "voicelink.password-reset.public-base-url=https://voicelink.example.test",
                "voicelink.password-reset.mail-api-key=offline-only",
                "voicelink.password-reset.mail-from-address=noreply@example.test"})
@ActiveProfiles("test")
class RoomLiveWebSocketTests {
    private static final String PASSWORD = "test-password-123";
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>");
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]+)\"");

    @Value("${local.server.port}") int port;
    @Autowired UserAccountRepository accounts;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired RoomParticipantRepository participants;
    @Autowired RoomService rooms;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordResetService resets;
    @Autowired AccountDeletionService accountDeletion;
    @Autowired TestPasswordResetNotifier resetNotifier;
    @Autowired RoomLiveHub liveHub;
    @Autowired TextSharingState textSharing;
    @MockitoBean SpeechToTextProvider stt;
    @MockitoBean(name = "geminiTranslationProvider") TranslationProvider translation;
    @MockitoBean(name = "geminiTextToSpeechProvider") TextToSpeechProvider tts;

    private UserAccount account(String label) {
        return accounts.save(new UserAccount(label, UUID.randomUUID() + "@example.test",
                encoder.encode(PASSWORD)));
    }

    private Browser browser(UserAccount account) throws Exception {
        Browser browser = new Browser(port, json);
        browser.login(account.getEmail());
        return browser;
    }

    @Test
    void passwordResetClosesExistingRoomSocket() throws Exception {
        UserAccount owner = account("Reset socket owner");
        UUID roomId = rooms.createRoom(owner.getId(), "en", "ta");
        Browser browser = browser(owner);
        LiveSocket socket = browser.connect(roomId, browser.base);
        socket.await("ROOM_STATE");
        resetNotifier.clear();
        resets.request(owner.getEmail());
        var message = resetNotifier.await();
        assertThat(message).isNotNull();
        assertThat(resets.reset(message.token(), "changed-password-456")).isTrue();
        socket.closed.get(8, TimeUnit.SECONDS);
        assertThat(socket.closeCode).isEqualTo(1008);
    }

    @Test
    void accountDeletionClosesBothSocketsAndNotifiesRemainingMember() throws Exception {
        UserAccount creator = account("Deleting socket owner");
        UserAccount peer = account("Remaining socket member");
        UUID roomId = rooms.createRoom(creator.getId(), "en", "ta");
        rooms.joinRoom(peer.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser first = browser(creator);
        Browser second = browser(peer);
        LiveSocket firstSocket = first.connect(roomId, first.base);
        LiveSocket secondSocket = second.connect(roomId, second.base);
        firstSocket.await("ROOM_STATE");
        secondSocket.await("ROOM_STATE");
        assertThat(accountDeletion.delete(creator.getId(), PASSWORD, "DELETE")).isTrue();
        assertThat(secondSocket.await("ROOM_CLOSED").path("type").textValue()).isEqualTo("ROOM_CLOSED");
        firstSocket.awaitClose();
        secondSocket.awaitClose();
        assertThat(accounts.findById(creator.getId())).isEmpty();
        assertThat(accounts.findById(peer.getId())).isPresent();
    }

    @Test
    void speechUploadPublishesSafeTurnEventsOverRealSocketWithoutProviderCredentials() throws Exception {
        var a = account("Speech A");
        var b = account("Speech B");
        UUID roomId = rooms.createRoomForSpeaker(a.getId(), "Room A", "en",
                com.mahroosdev.voicelink.ai.AiServiceProfile.STANDARD, true);
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket aSocket = first.connect(roomId, first.base);
        LiveSocket bSocket = second.connect(roomId, second.base);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        when(stt.transcribe(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.CONFIGURATION));
        UUID requestId = UUID.randomUUID();
        assertThat(first.upload(roomId, requestId, smallWebm())).isEqualTo(202);
        JsonNode acceptedA = aSocket.await("TURN_ACCEPTED");
        assertThat(acceptedA.path("payload").path("sourceLanguage").asText()).isEqualTo("en");
        assertThat(acceptedA.path("payload").path("targetLanguage").asText()).isEqualTo("ta");
        JsonNode failed = bSocket.await("TURN_FAILED");
        assertThat(bSocket.history).noneMatch(event -> "TURN_ACCEPTED".equals(event.path("type").asText()));
        assertThat(failed.path("payload").has("transcript")).isFalse();
        assertThat(failed.path("payload").path("stage").asText()).isEqualTo("STT");
        assertThat(failed.path("payload").path("code").asText()).isEqualTo("CONFIGURATION");
        assertThat(failed.toString()).doesNotContain("replace-with", "Bearer", "api.groq.com");
    }

    @Test
    void finishAndSendReviewStaysOffSocketsUntilAuthenticatedExplicitSend() throws Exception {
        var a = account("Private speech sender");
        var b = account("Private speech receiver");
        var other = account("Other speech room");
        UUID roomId = rooms.createRoomForSpeaker(a.getId(), "Room A", "en",
                com.mahroosdev.voicelink.ai.AiServiceProfile.STANDARD, true);
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        UUID otherRoom = rooms.createRoom(other.getId(), "ta", "en");
        Browser sender = browser(a), receiver = browser(b), separate = browser(other);
        LiveSocket senderSocket = sender.connect(roomId, sender.base);
        LiveSocket receiverSocket = receiver.connect(roomId, receiver.base);
        LiveSocket separateSocket = separate.connect(otherRoom, separate.base);
        senderSocket.await("ROOM_STATE");
        receiverSocket.await("ROOM_STATE");
        separateSocket.await("ROOM_STATE");
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result(
                "Hello Java", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம் Java", "GEMINI", "gemini-3.1-flash-lite", 1));
        when(tts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(
                new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));

        HttpResponse<String> upload = sender.upload(roomId, UUID.randomUUID(), smallWebm(),
                "FINISH_AND_SEND");
        assertThat(upload.statusCode()).isEqualTo(202);
        String turnId = json.readTree(upload.body()).path("turnId").asText();
        assertThat(senderSocket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
        assertThat(receiverSocket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
        assertThat(separateSocket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();

        JsonNode review = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpResponse<String> response = sender.get("/api/rooms/" + roomId + "/turns/" + turnId + "/review");
            assertThat(response.statusCode()).isEqualTo(200);
            review = json.readTree(response.body());
            if ("READY_FOR_REVIEW".equals(review.path("status").asText())) break;
            Thread.sleep(20);
        }
        assertThat(review.path("status").asText()).isEqualTo("READY_FOR_REVIEW");
        assertThat(review.path("transcript").asText()).isEqualTo("Hello Java");
        assertThat(review.path("translatedText").asText()).isEqualTo("வணக்கம் Java");
        assertThat(receiverSocket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
        assertThat(review.toString()).doesNotContain(sender.sessionId(), "api-key");

        assertThat(sender.post("/api/rooms/" + roomId + "/turns/" + turnId + "/send",
                "/rooms/" + roomId).statusCode()).isEqualTo(200);
        for (String type : List.of("TURN_ACCEPTED", "TRANSCRIPT_READY", "TRANSLATION_READY", "AUDIO_READY")) {
            JsonNode senderEvent = senderSocket.await(type);
            JsonNode receiverEvent = receiverSocket.await(type);
            assertThat(senderEvent.path("eventId").asText()).isEqualTo(receiverEvent.path("eventId").asText());
            assertThat(receiverEvent.toString()).doesNotContain("review", sender.sessionId());
        }
        assertThat(separateSocket.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
        verify(tts, times(1)).synthesize(any());
    }

    @Test
    void finalizedTranslatedTextUsesOrderedRoomIsolatedRealSocketEvents() throws Exception {
        var a = account("Text sender");
        var b = account("Text receiver");
        var other = account("Other room member");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        UUID otherRoom = rooms.createRoom(other.getId(), "ta", "en");
        Browser first = browser(a), second = browser(b), separate = browser(other);
        LiveSocket aSocket = first.connect(roomId, first.base);
        LiveSocket bSocket = second.connect(roomId, second.base);
        LiveSocket separateSocket = separate.connect(otherRoom, separate.base);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        separateSocket.await("ROOM_STATE");
        UUID participant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).getFirst().getId();
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        Map<String, String> payload = Map.of("originalText", "Hello", "translatedText", "வணக்கம்",
                "sourceLanguage", "en", "targetLanguage", "ta", "profile", "STANDARD");
        liveHub.publishTurn(roomId, a.getId(), participant, firstId, "TEXT_TRANSLATION_SENT", payload);
        JsonNode senderEvent = aSocket.await("TEXT_TRANSLATION_SENT");
        JsonNode receiverEvent = bSocket.await("TEXT_TRANSLATION_SENT");
        assertThat(senderEvent.path("eventId").asText()).isEqualTo(receiverEvent.path("eventId").asText());
        assertThat(receiverEvent.path("turnId").asText()).isEqualTo(firstId.toString());
        assertThat(receiverEvent.path("senderParticipantId").asText()).isEqualTo(participant.toString());
        assertThat(receiverEvent.path("payload").path("translatedText").asText()).isEqualTo("வணக்கம்");
        assertThat(separateSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        liveHub.publishTurn(roomId, a.getId(), participant, secondId, "TEXT_TRANSLATION_SENT", payload);
        JsonNode later = bSocket.await("TEXT_TRANSLATION_SENT");
        assertThat(later.path("sequence").asLong()).isGreaterThan(receiverEvent.path("sequence").asLong());
        assertThat(later.path("turnId").asText()).isEqualTo(secondId.toString());
    }

    @Test
    void authenticatedTextReviewLiveDraftAndFinalSendReachBothRoomBrowsersExactlyOnce() throws Exception {
        var a = account("Typed sender");
        var b = account("Typed receiver");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser sender = browser(a), receiver = browser(b);
        LiveSocket senderSocket = sender.connect(roomId, sender.base);
        LiveSocket receiverSocket = receiver.connect(roomId, receiver.base);
        senderSocket.await("ROOM_STATE");
        receiverSocket.await("ROOM_STATE");
        when(translation.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1));

        var start = sender.postJson("/api/rooms/" + roomId + "/text/shares",
                "/rooms/" + roomId, "{}");
        assertThat(start.statusCode()).isEqualTo(201);
        String epoch = json.readTree(start.body()).path("sharingEpoch").asText();
        var reviewResponse = sender.postJson("/api/rooms/" + roomId + "/text/reviews",
                "/rooms/" + roomId, json.writeValueAsString(Map.of(
                        "sourceText", "Hello", "revision", 1, "romanizedTamil", false,
                        "sharingEpoch", epoch)));
        assertThat(reviewResponse.statusCode()).isEqualTo(201);
        JsonNode review = json.readTree(reviewResponse.body());
        JsonNode senderDraft = senderSocket.await("TEXT_DRAFT_UPDATE");
        JsonNode receiverDraft = receiverSocket.await("TEXT_DRAFT_UPDATE");
        assertThat(receiverDraft.path("payload").path("translatedText").asText()).isEqualTo("வணக்கம்");
        assertThat(receiverDraft.path("payload").path("draftId").asText())
                .isEqualTo(senderDraft.path("payload").path("draftId").asText());

        var sentResponse = sender.postJson("/api/rooms/" + roomId + "/text/send",
                "/rooms/" + roomId, json.writeValueAsString(Map.of(
                        "reviewId", review.path("reviewId").asText(),
                        "sourceText", "Hello", "revision", 1)));
        assertThat(sentResponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(sentResponse.body()).path("draftId").asText())
                .isEqualTo(receiverDraft.path("payload").path("draftId").asText());
        senderSocket.await("TEXT_DRAFT_CLEAR");
        receiverSocket.await("TEXT_DRAFT_CLEAR");
        JsonNode senderFinal = senderSocket.await("TEXT_TRANSLATION_SENT");
        JsonNode receiverFinal = receiverSocket.await("TEXT_TRANSLATION_SENT");
        assertThat(receiverFinal.path("turnId").asText()).isEqualTo(senderFinal.path("turnId").asText());
        assertThat(receiverFinal.path("payload").path("originalText").asText()).isEqualTo("Hello");
        assertThat(receiverFinal.path("payload").path("translatedText").asText()).isEqualTo("வணக்கம்");
        assertThat(senderSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(receiverSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void livePreviewDraftIsSharedOnlyWithAuthenticatedRoomPeersAndClearedOnDisconnect() throws Exception {
        var a = account("Draft sender");
        var b = account("Draft receiver");
        var other = account("Separate receiver");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        UUID otherRoom = rooms.createRoom(other.getId(), "ta", "en");
        Browser sender = browser(a), receiver = browser(b), separate = browser(other);
        LiveSocket senderSocket = sender.connect(roomId, sender.base);
        LiveSocket receiverSocket = receiver.connect(roomId, receiver.base);
        LiveSocket separateSocket = separate.connect(otherRoom, separate.base);
        senderSocket.await("ROOM_STATE");
        receiverSocket.await("ROOM_STATE");
        separateSocket.await("ROOM_STATE");
        UUID participant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).getFirst().getId();

        var sharing = textSharing.start(roomId, a.getId(), participant, sender.sessionId(),
                liveHub::publishDraftClear);
        var lease = textSharing.begin(roomId, a.getId(), participant, sender.sessionId(),
                sharing.sharingEpoch(), 1, liveHub::publishDraftClear);
        assertThat(textSharing.publish(lease, "வணக்கம்", "en", "ta",
                liveHub::publishDraft)).isTrue();
        assertThat(senderSocket.await("TEXT_DRAFT_UPDATE").path("payload").path("translatedText").asText())
                .isEqualTo("வணக்கம்");
        assertThat(receiverSocket.await("TEXT_DRAFT_UPDATE").path("payload").path("translatedText").asText())
                .isEqualTo("வணக்கம்");
        assertThat(separateSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();

        senderSocket.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        assertThat(receiverSocket.await("TEXT_DRAFT_CLEAR").path("payload").path("reason").asText())
                .isEqualTo("DISCONNECTED");

        LiveSocket reconnected = sender.connect(roomId, sender.base);
        reconnected.await("ROOM_STATE");
        assertThat(receiverSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(reconnected.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private static byte[] smallWebm() {
        byte[] bytes = new byte[64];
        bytes[0] = 0x1a; bytes[1] = 0x45; bytes[2] = (byte) 0xdf; bytes[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 8, marker.length);
        return bytes;
    }

    @Test
    void waitingActivationAndBidirectionalDeliveryAreRealWebSocketEvents() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoomForSpeaker(a.getId(), "Room A", "en",
                com.mahroosdev.voicelink.ai.AiServiceProfile.STANDARD, true);
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket aSocket = first.connect(roomId, first.base);
        JsonNode waitingState = aSocket.await("ROOM_STATE");
        assertThat(waitingState.path("payload").path("status").textValue())
                .isEqualTo("WAITING");
        assertThat(waitingState.path("payload").path("participants").get(0).path("current").booleanValue())
                .isTrue();
        assertThat(waitingState.path("payload").path("participants").get(0).path("displayName").textValue())
                .isEqualTo("Room A");
        assertThat(waitingState.path("payload").path("participants").get(0).path("owner").booleanValue())
                .isTrue();
        aSocket.sendText("too soon");
        assertThat(aSocket.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("ROOM_NOT_ACTIVE");
        new TransactionTemplate(transactions).execute(ignored -> {
            rooms.joinRoomWithSetup(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode(), "Room B");
            try {
                assertThat(aSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return null;
        });
        JsonNode aState = aSocket.await("ROOM_STATE");
        assertThat(aState.path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        LiveSocket bSocket = second.connect(roomId, second.base);
        JsonNode bState = bSocket.await("ROOM_STATE");
        assertThat(bState.path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        UUID aParticipant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).getFirst().getId();
        UUID bParticipant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).getLast().getId();
        assertThat(aState.path("payload").path("participants")).anySatisfy(person -> {
            assertThat(person.path("participantId").textValue()).isEqualTo(aParticipant.toString());
            assertThat(person.path("current").booleanValue()).isTrue();
        });
        assertThat(bState.path("payload").path("participants")).anySatisfy(person -> {
            assertThat(person.path("participantId").textValue()).isEqualTo(bParticipant.toString());
            assertThat(person.path("current").booleanValue()).isTrue();
            assertThat(person.path("displayName").textValue()).isEqualTo("Room B");
            assertThat(person.path("owner").booleanValue()).isFalse();
        });
        aSocket.sendText("hello B");
        JsonNode aEcho = aSocket.await("TEXT_MESSAGE");
        JsonNode bReceive = bSocket.await("TEXT_MESSAGE");
        assertThat(bReceive.path("payload").path("text").textValue()).isEqualTo("hello B");
        assertThat(bReceive.path("senderParticipantId").textValue()).isEqualTo(aParticipant.toString());
        assertThat(aEcho.path("eventId").textValue()).isEqualTo(bReceive.path("eventId").textValue());
        bSocket.sendText("hello A");
        JsonNode aReceive = aSocket.await("TEXT_MESSAGE");
        JsonNode bEcho = bSocket.await("TEXT_MESSAGE");
        assertThat(aReceive.path("payload").path("text").textValue()).isEqualTo("hello A");
        assertThat(aReceive.path("sequence").longValue()).isGreaterThan(aEcho.path("sequence").longValue());
        assertThat(bEcho.path("eventId").textValue()).isEqualTo(aReceive.path("eventId").textValue());
        new TransactionTemplate(transactions).execute(ignored -> {
            rooms.closeRoom(b.getId(), roomId);
            try {
                assertThat(aSocket.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return null;
        });
        JsonNode closedA = aSocket.await("ROOM_CLOSED");
        JsonNode closedB = bSocket.await("ROOM_CLOSED");
        assertThat(closedA.path("sequence").longValue()).isGreaterThan(aReceive.path("sequence").longValue());
        assertThat(closedA.path("eventId").textValue()).isEqualTo(closedB.path("eventId").textValue());
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
    }

    @Test
    void handshakeRejectsAnonymousNonMemberClosedAndWrongOrigin() throws Exception {
        var a = account("A");
        var outsider = account("Outsider");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        Browser anonymous = new Browser(port, json);
        Browser member = browser(a);
        Browser nonMember = browser(outsider);
        assertThat(member.http.send(HttpRequest.newBuilder(URI.create(member.base + "/js/room-live.js"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(anonymous.http.send(HttpRequest.newBuilder(URI.create(anonymous.base + "/js/room-live.js"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
        assertThatThrownBy(() -> anonymous.connect(roomId, anonymous.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> nonMember.connect(roomId, nonMember.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> member.connect(UUID.randomUUID(), member.base)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> member.connect(roomId, "https://wrong.example")).isInstanceOf(Exception.class);
        rooms.closeRoom(a.getId(), roomId);
        assertThatThrownBy(() -> member.connect(roomId, member.base)).isInstanceOf(Exception.class);
    }

    @Test
    void validationReplacementAndLogoutRevocation() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        Browser first = browser(a);
        Browser second = browser(b);
        LiveSocket old = first.connect(roomId, first.base);
        old.await("ROOM_STATE");
        LiveSocket replacement = first.connect(roomId, first.base);
        replacement.await("ROOM_STATE");
        old.awaitClose();
        assertThat(old.closeCode).isEqualTo(4001);
        LiveSocket peer = second.connect(roomId, second.base);
        peer.await("ROOM_STATE");
        peer.sendText("after replacement");
        assertThat(replacement.await("TEXT_MESSAGE").path("payload").path("text").textValue())
                .isEqualTo("after replacement");
        peer.await("TEXT_MESSAGE");

        replacement.sendRaw("{");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendText("   ");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendText("x".repeat(1001));
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        replacement.sendRaw("{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                + UUID.randomUUID() + "\",\"text\":\"spoof\",\"senderParticipantId\":\""
                + UUID.randomUUID() + "\"}");
        assertThat(replacement.await("ERROR").path("payload").path("code").textValue())
                .isEqualTo("INVALID_MESSAGE");
        first.logout(roomId);
        replacement.awaitClose();
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
        assertThatThrownBy(() -> first.connect(roomId, first.base)).isInstanceOf(Exception.class);
        LiveSocket renewed = browser(a).connect(roomId, first.base);
        assertThat(renewed.await("ROOM_STATE").path("payload").path("status").textValue())
                .isEqualTo("ACTIVE");
        renewed.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        LiveSocket binary = browser(a).connect(roomId, first.base);
        binary.await("ROOM_STATE");
        binary.socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1}), true).join();
        binary.awaitClose();
    }

    @Test
    void separateRoomsNeverReceiveEachOthersTextAndDuplicateIdsAreIgnored() throws Exception {
        var a = account("A");
        var b = account("B");
        var c = account("C");
        var d = account("D");
        UUID firstRoom = rooms.createRoom(a.getId(), "en", "ta");
        UUID secondRoom = rooms.createRoom(c.getId(), "ta", "en");
        rooms.joinRoom(b.getId(), roomRepository.findById(firstRoom).orElseThrow().getJoinCode());
        rooms.joinRoom(d.getId(), roomRepository.findById(secondRoom).orElseThrow().getJoinCode());
        LiveSocket aSocket = browser(a).connect(firstRoom, "http://localhost:" + port);
        LiveSocket bSocket = browser(b).connect(firstRoom, "http://localhost:" + port);
        LiveSocket cSocket = browser(c).connect(secondRoom, "http://localhost:" + port);
        LiveSocket dSocket = browser(d).connect(secondRoom, "http://localhost:" + port);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        cSocket.await("ROOM_STATE");
        dSocket.await("ROOM_STATE");
        UUID id = UUID.randomUUID();
        String request = "{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                + id + "\",\"text\":\"room one\"}";
        aSocket.sendRaw(request);
        aSocket.sendRaw(request);
        assertThat(aSocket.await("TEXT_MESSAGE").path("payload").path("clientMessageId").textValue())
                .isEqualTo(id.toString());
        assertThat(aSocket.await("TEXT_MESSAGE").path("payload").path("clientMessageId").textValue())
                .isEqualTo(id.toString());
        assertThat(bSocket.await("TEXT_MESSAGE").path("payload").path("text").textValue())
                .isEqualTo("room one");
        assertThat(bSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(cSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(dSocket.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        rooms.closeRoom(a.getId(), firstRoom);
        bSocket.await("ROOM_CLOSED");
        assertThat(roomRepository.findById(secondRoom).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
    }

    @Test
    void concurrentSendsShareOneOrderAndClosedEndsTheStream() throws Exception {
        var a = account("A");
        var b = account("B");
        UUID roomId = rooms.createRoom(a.getId(), "en", "ta");
        rooms.joinRoom(b.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        LiveSocket aSocket = browser(a).connect(roomId, "http://localhost:" + port);
        LiveSocket bSocket = browser(b).connect(roomId, "http://localhost:" + port);
        aSocket.await("ROOM_STATE");
        bSocket.await("ROOM_STATE");
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) aSocket.sendText("A" + i);
                return null;
            });
            var second = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) bSocket.sendText("B" + i);
                return null;
            });
            start.countDown();
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
            List<JsonNode> aEvents = new ArrayList<>();
            List<JsonNode> bEvents = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                aEvents.add(aSocket.await("TEXT_MESSAGE"));
                bEvents.add(bSocket.await("TEXT_MESSAGE"));
            }
            assertThat(aEvents.stream().map(e -> e.path("eventId").textValue()).toList())
                    .containsExactlyElementsOf(
                            bEvents.stream().map(e -> e.path("eventId").textValue()).toList());
            for (int i = 1; i < aEvents.size(); i++) {
                assertThat(aEvents.get(i).path("sequence").longValue())
                        .isGreaterThan(aEvents.get(i - 1).path("sequence").longValue());
            }
            CountDownLatch race = new CountDownLatch(1);
            var sender = workers.submit(() -> {
                race.await();
                try { aSocket.sendText("racing close"); } catch (RuntimeException ignored) {}
                return null;
            });
            var closer = workers.submit(() -> {
                race.await();
                rooms.closeRoom(b.getId(), roomId);
                return null;
            });
            race.countDown();
            sender.get(8, TimeUnit.SECONDS);
            closer.get(8, TimeUnit.SECONDS);
            JsonNode closedA = aSocket.await("ROOM_CLOSED");
            JsonNode closedB = bSocket.await("ROOM_CLOSED");
            assertThat(closedA.path("eventId").textValue()).isEqualTo(closedB.path("eventId").textValue());
            assertThat(aSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(bSocket.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    private static final class Browser {
        final int port;
        final String base;
        final HttpClient http;
        final ObjectMapper json;
        final CookieManager cookies;

        Browser(int port, ObjectMapper json) {
            this.port = port;
            this.base = "http://localhost:" + port;
            this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            this.json = json;
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
        }

        void login(String email) throws Exception {
            String token = csrf("/login");
            String form = "email=" + encode(email) + "&password=" + encode(PASSWORD)
                    + "&_csrf=" + encode(token);
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("location").orElse("")).endsWith("/app");
        }

        void logout(UUID roomId) throws Exception {
            String token = csrf("/rooms/" + roomId);
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/logout"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(token))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
        }

        String csrf(String path) throws Exception {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var input = CSRF_INPUT.matcher(response.body());
            assertThat(input.find()).isTrue();
            var value = VALUE.matcher(input.group());
            assertThat(value.find()).isTrue();
            return value.group(1);
        }

        LiveSocket connect(UUID roomId, String origin) throws Exception {
            LiveSocket listener = new LiveSocket(json);
            listener.socket = http.newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms/" + roomId), listener)
                    .get(10, TimeUnit.SECONDS);
            return listener;
        }

        int upload(UUID roomId, UUID requestId, byte[] audio) throws Exception {
            return upload(roomId, requestId, audio, "LIVE_SEND").statusCode();
        }

        HttpResponse<String> upload(UUID roomId, UUID requestId, byte[] audio,
                                    String workflow) throws Exception {
            String boundary = "test-" + UUID.randomUUID();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            multipartField(body, boundary, "clientRequestId", requestId.toString());
            multipartField(body, boundary, "durationMillis", "1000");
            multipartField(body, boundary, "workflow", workflow);
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turn.webm\"\r\n"
                    + "Content-Type: audio/webm;codecs=opus\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(audio);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.send(HttpRequest.newBuilder(URI.create(base + "/api/rooms/" + roomId + "/turns"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-CSRF-TOKEN", csrf("/rooms/" + roomId))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String csrfPath) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("X-CSRF-TOKEN", csrf(csrfPath))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String csrfPath, String body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", csrf(csrfPath))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private static void multipartField(java.io.ByteArrayOutputStream body, String boundary,
                                           String name, String value) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                    + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static final class LiveSocket implements WebSocket.Listener {
        final ObjectMapper json;
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final java.util.concurrent.CopyOnWriteArrayList<JsonNode> history = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();
        final StringBuilder current = new StringBuilder();
        WebSocket socket;
        volatile int closeCode;

        LiveSocket(ObjectMapper json) { this.json = json; }

        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                try {
                    JsonNode event = json.readTree(current.toString());
                    history.add(event);
                    messages.add(event);
                }
                catch (Exception ex) { closed.completeExceptionally(ex); }
                current.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }

        JsonNode await(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < until) {
                JsonNode event = messages.poll(1, TimeUnit.SECONDS);
                if (event != null && type.equals(event.path("type").textValue())) return event;
            }
            throw new AssertionError("Timed out waiting for " + type);
        }

        void awaitClose() throws Exception { closed.get(8, TimeUnit.SECONDS); }

        void sendText(String value) {
            sendRaw("{\"protocolVersion\":1,\"type\":\"SEND_TEXT\",\"clientMessageId\":\""
                    + UUID.randomUUID() + "\",\"text\":" + json.writeValueAsString(value) + "}");
        }

        void sendRaw(String value) { socket.sendText(value, true).join(); }
    }
}
