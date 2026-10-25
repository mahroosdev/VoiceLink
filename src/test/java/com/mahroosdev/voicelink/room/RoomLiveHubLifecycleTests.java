package com.mahroosdev.voicelink.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.conversation.TextSharingState;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RoomLiveHubLifecycleTests {
    private static final String PASSWORD_HASH = "test-hash";
    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID participantId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T00:00:00Z"));
    private final RoomLiveAuthorizer authorizer = mock(RoomLiveAuthorizer.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private RoomLiveHub hub;
    private RoomStatus status = RoomStatus.ACTIVE;

    @BeforeEach
    void setUp() throws Exception {
        TextSharingState sharing = new TextSharingState();
        hub = new RoomLiveHub(authorizer, json, accounts, sharing, clock);
        when(authorizer.snapshot(userId, roomId)).thenAnswer(call ->
                new RoomLiveAuthorizer.Snapshot(status, List.of()));
        when(authorizer.authorize(userId, roomId)).thenAnswer(call ->
                new RoomLiveAuthorizer.Member(participantId, status, "en", "ta", AiServiceProfile.STANDARD));
        when(accounts.findById(userId)).thenReturn(Optional.of(
                new UserAccount("Test", "test@example.test", PASSWORD_HASH)));
    }

    @Test
    void activeQuietRoomIsNeverExpired() throws Exception {
        hub.register(socket(), roomId, userId, session());
        clock.advance(Duration.ofDays(3));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isEqualTo(1);
    }

    @Test
    void idleStreamExpiresOnlyAfterThirtyFiveMinutes() throws Exception {
        RoomLiveHub.Connection connection = hub.register(socket(), roomId, userId, session());
        hub.unregister(connection);
        clock.advance(Duration.ofMinutes(35).minusMillis(1));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isEqualTo(1);
        clock.advance(Duration.ofMillis(1));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isZero();
    }

    @Test
    void reconnectBeforeExpiryPreservesStreamAndPreventsStaleCleanup() throws Exception {
        RoomLiveHub.Connection first = hub.register(socket(), roomId, userId, session());
        String initialStreamId = latestEvent().path("streamId").asText();
        hub.unregister(first);
        clock.advance(Duration.ofMinutes(34));
        RoomLiveHub.Connection reconnected = hub.register(socket(), roomId, userId, session());
        String reconnectedStreamId = latestEvent().path("streamId").asText();
        clock.advance(Duration.ofMinutes(2));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isEqualTo(1);
        assertThat(reconnectedStreamId).isEqualTo(initialStreamId);
        assertThat(hub.hasCurrentConnection(roomId, userId, reconnected.httpSessionId)).isTrue();
    }

    @Test
    void reconnectAfterExpiryStartsANewStream() throws Exception {
        RoomLiveHub.Connection first = hub.register(socket(), roomId, userId, session());
        String oldStreamId = latestEvent().path("streamId").asText();
        hub.unregister(first);
        clock.advance(Duration.ofMinutes(35));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isZero();
        hub.register(socket(), roomId, userId, session());
        assertThat(latestEvent().path("streamId").asText()).isNotEqualTo(oldStreamId);
    }

    @Test
    void explicitClosureRemovesStreamAndLaterRegistrationCannotRecreateIt() throws Exception {
        hub.register(socket(), roomId, userId, session());
        hub.onClosed(new RoomClosedEvent(roomId));
        assertThat(hub.streamCount()).isZero();
        status = RoomStatus.CLOSED;
        assertThat(hub.register(socket(), roomId, userId, session())).isNull();
        assertThat(hub.streamCount()).isZero();
    }

    @Test
    void registrationAfterCloseEventWhenNoStreamExistedDoesNotLeakClosedStream() throws Exception {
        // Reproduces closure committing before the authorized handshake reaches hub.register().
        hub.onClosed(new RoomClosedEvent(roomId));
        status = RoomStatus.CLOSED;
        assertThat(hub.register(socket(), roomId, userId, session())).isNull();
        assertThat(hub.streamCount()).isZero();
    }

    @Test
    void registrationObservingCommittedClosureAlsoClosesEarlierSockets() throws Exception {
        WebSocketSession existingSocket = socket();
        hub.register(existingSocket, roomId, userId, session());
        status = RoomStatus.CLOSED;
        assertThat(hub.register(socket(), roomId, userId, session())).isNull();
        verify(existingSocket).close(CloseStatus.NORMAL);
        assertThat(hub.streamCount()).isZero();
    }

    @Test
    void concurrentRoomClosureAndRegistrationCannotLeaveOrphanedStream() throws Exception {
        status = RoomStatus.CLOSED;
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var registration = executor.submit(() -> {
                start.await();
                return hub.register(socket(), roomId, userId, session());
            });
            var closure = executor.submit(() -> {
                start.await();
                hub.onClosed(new RoomClosedEvent(roomId));
                return null;
            });
            start.countDown();
            registration.get();
            closure.get();
            assertThat(hub.streamCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupRacingWithReconnectNeverRemovesAnAcceptedConnection() throws Exception {
        RoomLiveHub.Connection initial = hub.register(socket(), roomId, userId, session());
        hub.unregister(initial);
        clock.advance(Duration.ofMinutes(35));
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var cleanup = executor.submit(() -> {
                start.await();
                hub.cleanupInactiveStreams(clock.instant());
                return null;
            });
            var registration = executor.submit(() -> {
                start.await();
                return hub.register(socket(), roomId, userId, session());
            });
            start.countDown();
            RoomLiveHub.Connection reconnected = registration.get();
            cleanup.get();
            if (reconnected == null) assertThat(hub.streamCount()).isZero();
            else {
                assertThat(hub.streamCount()).isEqualTo(1);
                assertThat(hub.hasCurrentConnection(roomId, userId, reconnected.httpSessionId)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameMessageIdReturnsOriginalReceiptAndReceiptSurvivesThirtyFiveMinuteWindow() throws Exception {
        RoomLiveHub.Connection connection = hub.register(socket(), roomId, userId, session());
        UUID messageId = UUID.randomUUID();
        hub.sendText(connection, messageId, "original");
        clock.advance(Duration.ofMinutes(34).plusSeconds(59));
        hub.sendText(connection, messageId, "changed retry");
        JsonNode duplicate = latestEvent();
        assertThat(duplicate.path("type").asText()).isEqualTo("TEXT_MESSAGE");
        assertThat(duplicate.path("payload").path("text").asText()).isEqualTo("original");
        assertThat(duplicate.path("payload").path("clientMessageId").asText()).isEqualTo(messageId.toString());
    }

    @Test
    void receiptExpiresOnlyAfterRetentionWindow() throws Exception {
        RoomLiveHub.Connection connection = hub.register(socket(), roomId, userId, session());
        UUID messageId = UUID.randomUUID();
        hub.sendText(connection, messageId, "first");
        clock.advance(Duration.ofMinutes(35));
        hub.cleanupInactiveStreams(clock.instant());
        assertThat(hub.streamCount()).isEqualTo(1);
        hub.sendText(connection, messageId, "new attempt after retention");
        assertThat(latestEvent().path("payload").path("text").asText()).isEqualTo("new attempt after retention");
    }

    @Test
    void quickChatRateLimitKeepsReceiptCapacityBelowHardBound() throws Exception {
        RoomLiveHub.Connection connection = hub.register(socket(), roomId, userId, session());
        for (int i = 0; i < RoomLiveHub.MAX_QUICK_CHAT_MESSAGES_PER_PARTICIPANT; i++)
            hub.sendText(connection, UUID.randomUUID(), "message " + i);
        hub.sendText(connection, UUID.randomUUID(), "over limit");
        JsonNode error = latestEvent();
        assertThat(error.path("type").asText()).isEqualTo("ERROR");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(RoomLiveHub.MAX_RECEIPTS_PER_PARTICIPANT)
                .isGreaterThan(RoomLiveHub.MAX_QUICK_CHAT_MESSAGES_PER_PARTICIPANT);
    }

    private WebSocketSession socket() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.isOpen()).thenReturn(true);
        doAnswer(call -> {
            sent.add(((TextMessage) call.getArgument(0)).getPayload());
            return null;
        }).when(socket).sendMessage(any(TextMessage.class));
        return socket;
    }

    private HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.getMaxInactiveInterval()).thenReturn(-1);
        var principal = new AccountPrincipal(userId, "test@example.test", PASSWORD_HASH, true);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(new SecurityContextImpl(authentication));
        return session;
    }

    private JsonNode latestEvent() throws Exception {
        return json.readTree(sent.getLast());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
    }
}
