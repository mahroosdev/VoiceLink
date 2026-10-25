package com.mahroosdev.voicelink.room;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.auth.PasswordResetCompletedEvent;
import com.mahroosdev.voicelink.auth.AccountDeletedEvent;
import com.mahroosdev.voicelink.conversation.TextSharingState;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

@Component
public class RoomLiveHub {
    private static final CloseStatus REPLACED = new CloseStatus(4001, "Replaced by a newer connection");
    static final Duration STREAM_IDLE_RETENTION = Duration.ofMinutes(35);
    static final Duration RECEIPT_RETENTION = Duration.ofMinutes(35);
    static final int MAX_RECEIPTS_PER_PARTICIPANT = 64;
    static final int MAX_QUICK_CHAT_MESSAGES_PER_PARTICIPANT = 60;
    private final ConcurrentHashMap<UUID, Stream> streams = new ConcurrentHashMap<>();
    private final RoomLiveAuthorizer authorizer;
    private final ObjectMapper json;
    private final UserAccountRepository accounts;
    private final TextSharingState textSharing;
    private final Clock clock;

    @Autowired
    public RoomLiveHub(RoomLiveAuthorizer authorizer, ObjectMapper json, UserAccountRepository accounts,
                       TextSharingState textSharing) {
        this(authorizer, json, accounts, textSharing, Clock.systemUTC());
    }

    RoomLiveHub(RoomLiveAuthorizer authorizer, ObjectMapper json, UserAccountRepository accounts,
                TextSharingState textSharing, Clock clock) {
        this.authorizer = authorizer;
        this.json = json;
        this.accounts = accounts;
        this.textSharing = textSharing;
        this.clock = clock;
    }

    public Connection register(WebSocketSession socket, UUID roomId, UUID userId, HttpSession httpSession) {
        Stream stream = streams.computeIfAbsent(roomId, ignored -> new Stream());
        Connection connection = new Connection(socket, roomId, userId, httpSession, stream, accounts);
        Connection previous;
        synchronized (stream) {
            if (!connection.valid()) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                markIdleIfEmpty(stream);
                return null;
            }
            RoomLiveAuthorizer.Snapshot snapshot;
            try {
                snapshot = authorizer.snapshot(userId, roomId);
            } catch (RuntimeException ex) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                markIdleIfEmpty(stream);
                return null;
            }
            if (snapshot.status() == RoomStatus.CLOSED) closeStreamLocked(stream, roomId);
            if (stream.closed) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                streams.remove(roomId, stream);
                return null;
            }
            previous = stream.members.put(userId, connection);
            stream.idleSince = null;
            send(stream, connection, event(stream, roomId, "ROOM_STATE", null, null,
                    Map.of("status", snapshot.status().name(), "participants", snapshot.participants())));
        }
        if (previous != null) {
            textSharing.disconnect(roomId, userId, previous.httpSessionId, this::publishDraftClear);
            close(previous, REPLACED);
        }
        return connection;
    }

    public void unregister(Connection connection) {
        Stream stream = connection.stream;
        synchronized (stream) {
            removeMemberLocked(stream, connection);
        }
        textSharing.disconnect(connection.roomId, connection.userId,
                connection.httpSessionId, this::publishDraftClear);
    }

    public boolean hasCurrentConnection(UUID roomId, UUID userId, String sessionId) {
        Stream stream = streams.get(roomId);
        if (stream == null) return false;
        synchronized (stream) {
            Connection connection = stream.members.get(userId);
            return connection != null && connection.httpSessionId.equals(sessionId) && connection.valid();
        }
    }

    public void sendText(Connection connection, UUID clientMessageId, String text) {
        Stream stream = connection.stream;
        synchronized (stream) {
            if (stream.members.get(connection.userId) != connection) return;
            if (!connection.valid()) {
                removeMemberLocked(stream, connection);
                close(connection, CloseStatus.POLICY_VIOLATION);
                return;
            }
            RoomLiveAuthorizer.Member member;
            try {
                member = authorizer.authorize(connection.userId, connection.roomId);
            } catch (RuntimeException ex) {
                errorLocked(connection, "NOT_AUTHORIZED", "Live room access is unavailable.");
                close(connection, CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (stream.closed || member.status() != RoomStatus.ACTIVE) {
                errorLocked(connection, "ROOM_NOT_ACTIVE", "This room is not active.");
                return;
            }
            LinkedHashMap<UUID, TextReceipt> seen = stream.clientIds.computeIfAbsent(connection.userId,
                    ignored -> new LinkedHashMap<>());
            pruneReceipts(seen, clock.instant());
            TextReceipt prior = seen.get(clientMessageId);
            if (prior != null) {
                send(stream, connection, event(stream, connection.roomId, "TEXT_MESSAGE",
                        prior.senderParticipantId(), UUID.randomUUID(),
                        Map.of("text", prior.text(), "clientMessageId", clientMessageId)));
                return;
            }
            if (seen.size() >= MAX_QUICK_CHAT_MESSAGES_PER_PARTICIPANT) {
                errorLocked(connection, "RATE_LIMITED",
                        "Quick chat limit reached. Try again after older messages expire.");
                return;
            }
            if (seen.size() >= MAX_RECEIPTS_PER_PARTICIPANT) {
                errorLocked(connection, "RATE_LIMITED",
                        "Quick chat receipts are full. Try again after older messages expire.");
                return;
            }
            seen.put(clientMessageId, new TextReceipt(member.participantId(), text, clock.instant()));
            LiveRoomEvent event = event(stream, connection.roomId, "TEXT_MESSAGE",
                    member.participantId(), UUID.randomUUID(),
                    Map.of("text", text, "clientMessageId", clientMessageId));
            for (Connection receiver : List.copyOf(stream.members.values())) {
                if (!receiver.valid()) {
                    removeMemberLocked(stream, receiver);
                    close(receiver, CloseStatus.POLICY_VIOLATION);
                } else {
                    send(stream, receiver, event);
                }
            }
        }
    }

    public void error(Connection connection, String code, String message) {
        synchronized (connection.stream) {
            if (connection.stream.members.get(connection.userId) == connection && connection.valid()) {
                errorLocked(connection, code, message);
            }
        }
    }

    public void publishTurn(UUID roomId, UUID userId, UUID participantId, UUID turnId,
                            String type, Map<String, ?> payload) {
        publishTurn(roomId, userId, participantId, turnId, type, payload, false);
    }

    public void publishTurnToSender(UUID roomId, UUID userId, UUID participantId, UUID turnId,
                                    String type, Map<String, ?> payload) {
        publishTurn(roomId, userId, participantId, turnId, type, payload, true);
    }

    private void publishTurn(UUID roomId, UUID userId, UUID participantId, UUID turnId,
                             String type, Map<String, ?> payload, boolean senderOnly) {
        RoomLiveAuthorizer.Member member;
        try {
            member = authorizer.authorize(userId, roomId);
        } catch (RuntimeException ex) {
            return;
        }
        if (member.status() != RoomStatus.ACTIVE || !member.participantId().equals(participantId)) return;
        Stream stream = streams.get(roomId);
        if (stream == null) return;
        synchronized (stream) {
            if (stream.closed) return;
            LiveRoomEvent event = event(stream, roomId, type, participantId, turnId, payload);
            for (Connection receiver : List.copyOf(stream.members.values())) {
                if (senderOnly && !receiver.userId.equals(userId)) continue;
                if (!receiver.valid()) {
                    removeMemberLocked(stream, receiver);
                    close(receiver, CloseStatus.POLICY_VIOLATION);
                } else {
                    send(stream, receiver, event);
                }
            }
        }
    }

    public boolean publishDraft(TextSharingState.DraftUpdate draft) {
        return false;
    }

    public void publishDraftClear(TextSharingState.DraftClear draft) {
        // Live previews are private and are never emitted as WebSocket events.
    }

    private void errorLocked(Connection connection, String code, String message) {
        send(connection.stream, connection,
                event(connection.stream, connection.roomId, "ERROR", null, null,
                        Map.of("code", code, "message", message)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(RoomActivatedEvent activated) {
        Stream stream = streams.get(activated.roomId());
        if (stream == null) return;
        synchronized (stream) {
            if (stream.closed) return;
            for (Connection connection : List.copyOf(stream.members.values())) {
                if (!connection.valid()) {
                    removeMemberLocked(stream, connection);
                    close(connection, CloseStatus.POLICY_VIOLATION);
                    continue;
                }
                try {
                    var snapshot = authorizer.snapshot(connection.userId, activated.roomId());
                    send(stream, connection, event(stream, activated.roomId(), "ROOM_STATE", null, null,
                            Map.of("status", snapshot.status().name(),
                                    "participants", snapshot.participants())));
                } catch (RuntimeException ex) {
                    removeMemberLocked(stream, connection);
                    close(connection, CloseStatus.POLICY_VIOLATION);
                }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(RoomClosedEvent closed) {
        Stream stream = streams.get(closed.roomId());
        if (stream == null) return;
        synchronized (stream) {
            closeStreamLocked(stream, closed.roomId());
        }
        streams.remove(closed.roomId(), stream);
    }

    public void revokeHttpSession(String sessionId) {
        List<Connection> removed = new java.util.ArrayList<>();
        for (Stream stream : streams.values()) {
            synchronized (stream) {
                for (Connection connection : List.copyOf(stream.members.values())) {
                    if (connection.httpSessionId.equals(sessionId)) {
                        removeMemberLocked(stream, connection);
                        removed.add(connection);
                        close(connection, CloseStatus.POLICY_VIOLATION);
                    }
                }
            }
        }
        removed.forEach(connection -> textSharing.disconnect(connection.roomId, connection.userId,
                connection.httpSessionId, this::publishDraftClear));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordReset(PasswordResetCompletedEvent reset) {
        List<Connection> removed = new java.util.ArrayList<>();
        for (Stream stream : streams.values()) {
            synchronized (stream) {
                Connection connection = stream.members.remove(reset.userId());
                if (connection != null) {
                    markIdleIfEmpty(stream);
                    removed.add(connection);
                    close(connection, CloseStatus.POLICY_VIOLATION);
                }
            }
        }
        removed.forEach(connection -> textSharing.disconnect(connection.roomId, connection.userId,
                connection.httpSessionId, this::publishDraftClear));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(AccountDeletedEvent deleted) {
        List<Connection> removed = new java.util.ArrayList<>();
        for (Stream stream : streams.values()) {
            synchronized (stream) {
                Connection connection = stream.members.remove(deleted.userId());
                if (connection != null) {
                    markIdleIfEmpty(stream);
                    removed.add(connection);
                    close(connection, CloseStatus.POLICY_VIOLATION);
                }
            }
        }
        removed.forEach(connection -> textSharing.disconnect(connection.roomId, connection.userId,
                connection.httpSessionId, this::publishDraftClear));
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupInactiveStreams() {
        cleanupInactiveStreams(clock.instant());
    }

    void cleanupInactiveStreams(Instant now) {
        for (Map.Entry<UUID, Stream> entry : streams.entrySet()) {
            UUID roomId = entry.getKey();
            Stream stream = entry.getValue();
            boolean expired = false;
            synchronized (stream) {
                for (LinkedHashMap<UUID, TextReceipt> receipts : stream.clientIds.values()) {
                    pruneReceipts(receipts, now);
                }
                stream.clientIds.values().removeIf(Map::isEmpty);
                if (!stream.closed && stream.members.isEmpty() && stream.idleSince != null
                        && !now.isBefore(stream.idleSince.plus(STREAM_IDLE_RETENTION))) {
                    stream.closed = true;
                    stream.clientIds.clear();
                    expired = true;
                }
            }
            if (expired) streams.remove(roomId, stream);
        }
    }

    int streamCount() { return streams.size(); }

    private void removeMemberLocked(Stream stream, Connection connection) {
        if (stream.members.remove(connection.userId, connection)) markIdleIfEmpty(stream);
    }

    private void closeStreamLocked(Stream stream, UUID roomId) {
        if (stream.closed) return;
        stream.closed = true;
        LiveRoomEvent event = event(stream, roomId, "ROOM_CLOSED", null, null, Map.of());
        for (Connection connection : List.copyOf(stream.members.values())) {
            if (connection.valid()) send(stream, connection, event);
            close(connection, CloseStatus.NORMAL);
        }
        stream.members.clear();
        stream.clientIds.clear();
    }

    private void markIdleIfEmpty(Stream stream) {
        if (stream.members.isEmpty() && stream.idleSince == null) stream.idleSince = clock.instant();
    }

    private static void pruneReceipts(LinkedHashMap<UUID, TextReceipt> receipts, Instant now) {
        Iterator<Map.Entry<UUID, TextReceipt>> iterator = receipts.entrySet().iterator();
        while (iterator.hasNext()) {
            TextReceipt receipt = iterator.next().getValue();
            if (!now.isBefore(receipt.processedAt().plus(RECEIPT_RETENTION))) iterator.remove();
        }
    }

    private LiveRoomEvent event(Stream stream, UUID roomId, String type,
                                UUID senderParticipantId, UUID turnId, Map<String, ?> payload) {
        return new LiveRoomEvent(1, UUID.randomUUID(), stream.streamId, roomId, ++stream.sequence,
                type, senderParticipantId, turnId, Instant.now(), payload);
    }

    private void send(Stream stream, Connection connection, LiveRoomEvent event) {
        try {
            connection.outbound.sendMessage(new TextMessage(json.writeValueAsString(event)));
        } catch (Exception ex) {
            removeMemberLocked(stream, connection);
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(Connection connection, CloseStatus status) {
        try {
            if (connection.socket.isOpen()) connection.socket.close(status);
        } catch (IOException ignored) {
            // Connection is already unavailable.
        }
    }

    private static final class Stream {
        final UUID streamId = UUID.randomUUID();
        final Map<UUID, Connection> members = new HashMap<>();
        final Map<UUID, LinkedHashMap<UUID, TextReceipt>> clientIds = new HashMap<>();
        Instant idleSince;
        long sequence;
        boolean closed;
    }

    private record TextReceipt(UUID senderParticipantId, String text, Instant processedAt) {}

    public static final class Connection {
        final WebSocketSession socket;
        final ConcurrentWebSocketSessionDecorator outbound;
        final UUID roomId;
        final UUID userId;
        final HttpSession httpSession;
        final String httpSessionId;
        final Stream stream;
        final UserAccountRepository accounts;

        Connection(WebSocketSession socket, UUID roomId, UUID userId,
                   HttpSession httpSession, Stream stream, UserAccountRepository accounts) {
            this.socket = socket;
            this.outbound = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536);
            this.roomId = roomId;
            this.userId = userId;
            this.httpSession = httpSession;
            this.httpSessionId = httpSession.getId();
            this.stream = stream;
            this.accounts = accounts;
        }

        boolean valid() {
            try {
                int timeout = httpSession.getMaxInactiveInterval();
                if (timeout > 0 && System.currentTimeMillis() - httpSession.getLastAccessedTime()
                        >= timeout * 1000L) return false;
                Object context = httpSession.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if (!(context instanceof SecurityContext security)) return false;
                Authentication authentication = security.getAuthentication();
                return authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof AccountPrincipal principal
                        && principal.isEnabled() && principal.getUserId().equals(userId)
                        && accounts.findById(userId).filter(account -> account.isEnabled()
                                && account.getPasswordHash().equals(principal.getPassword())).isPresent();
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }
}
