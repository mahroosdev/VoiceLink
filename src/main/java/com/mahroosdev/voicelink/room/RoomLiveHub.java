package com.mahroosdev.voicelink.room;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
    private final ConcurrentHashMap<UUID, Stream> streams = new ConcurrentHashMap<>();
    private final RoomLiveAuthorizer authorizer;
    private final ObjectMapper json;

    public RoomLiveHub(RoomLiveAuthorizer authorizer, ObjectMapper json) {
        this.authorizer = authorizer;
        this.json = json;
    }

    public Connection register(WebSocketSession socket, UUID roomId, UUID userId, HttpSession httpSession) {
        Stream stream = streams.computeIfAbsent(roomId, ignored -> new Stream());
        Connection connection = new Connection(socket, roomId, userId, httpSession, stream);
        Connection previous;
        synchronized (stream) {
            if (!connection.valid()) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return null;
            }
            RoomLiveAuthorizer.Snapshot snapshot;
            try {
                snapshot = authorizer.snapshot(userId, roomId);
            } catch (RuntimeException ex) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return null;
            }
            if (stream.closed || snapshot.status() == RoomStatus.CLOSED) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return null;
            }
            previous = stream.members.put(userId, connection);
            send(stream, connection, event(stream, roomId, "ROOM_STATE", null, null,
                    Map.of("status", snapshot.status().name(), "participants", snapshot.participants())));
        }
        if (previous != null) close(previous, REPLACED);
        return connection;
    }

    public void unregister(Connection connection) {
        Stream stream = connection.stream;
        synchronized (stream) {
            stream.members.remove(connection.userId, connection);
        }
    }

    public void sendText(Connection connection, UUID clientMessageId, String text) {
        Stream stream = connection.stream;
        synchronized (stream) {
            if (stream.members.get(connection.userId) != connection) return;
            if (!connection.valid()) {
                stream.members.remove(connection.userId, connection);
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
            LinkedHashSet<UUID> seen = stream.clientIds.computeIfAbsent(connection.userId,
                    ignored -> new LinkedHashSet<>());
            if (!seen.add(clientMessageId)) return;
            if (seen.size() > 128) {
                Iterator<UUID> ids = seen.iterator();
                ids.next();
                ids.remove();
            }
            LiveRoomEvent event = event(stream, connection.roomId, "TEXT_MESSAGE",
                    member.participantId(), UUID.randomUUID(),
                    Map.of("text", text, "clientMessageId", clientMessageId));
            for (Connection receiver : List.copyOf(stream.members.values())) {
                if (!receiver.valid()) {
                    stream.members.remove(receiver.userId, receiver);
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
                if (!receiver.valid()) {
                    stream.members.remove(receiver.userId, receiver);
                    close(receiver, CloseStatus.POLICY_VIOLATION);
                } else {
                    send(stream, receiver, event);
                }
            }
        }
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
                    stream.members.remove(connection.userId, connection);
                    close(connection, CloseStatus.POLICY_VIOLATION);
                    continue;
                }
                try {
                    var snapshot = authorizer.snapshot(connection.userId, activated.roomId());
                    send(stream, connection, event(stream, activated.roomId(), "ROOM_STATE", null, null,
                            Map.of("status", snapshot.status().name(),
                                    "participants", snapshot.participants())));
                } catch (RuntimeException ex) {
                    stream.members.remove(connection.userId, connection);
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
            if (stream.closed) return;
            stream.closed = true;
            LiveRoomEvent event = event(stream, closed.roomId(), "ROOM_CLOSED", null, null, Map.of());
            for (Connection connection : List.copyOf(stream.members.values())) {
                if (connection.valid()) send(stream, connection, event);
                close(connection, CloseStatus.NORMAL);
            }
            stream.members.clear();
        }
        streams.remove(closed.roomId(), stream);
    }

    public void revokeHttpSession(String sessionId) {
        for (Stream stream : streams.values()) {
            synchronized (stream) {
                for (Connection connection : List.copyOf(stream.members.values())) {
                    if (connection.httpSessionId.equals(sessionId)) {
                        stream.members.remove(connection.userId, connection);
                        close(connection, CloseStatus.POLICY_VIOLATION);
                    }
                }
            }
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
            stream.members.remove(connection.userId, connection);
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
        final Map<UUID, LinkedHashSet<UUID>> clientIds = new HashMap<>();
        long sequence;
        boolean closed;
    }

    public static final class Connection {
        final WebSocketSession socket;
        final ConcurrentWebSocketSessionDecorator outbound;
        final UUID roomId;
        final UUID userId;
        final HttpSession httpSession;
        final String httpSessionId;
        final Stream stream;

        Connection(WebSocketSession socket, UUID roomId, UUID userId,
                   HttpSession httpSession, Stream stream) {
            this.socket = socket;
            this.outbound = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536);
            this.roomId = roomId;
            this.userId = userId;
            this.httpSession = httpSession;
            this.httpSessionId = httpSession.getId();
            this.stream = stream;
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
                        && principal.isEnabled() && principal.getUserId().equals(userId);
            } catch (IllegalStateException ex) {
                return false;
            }
        }
    }
}
