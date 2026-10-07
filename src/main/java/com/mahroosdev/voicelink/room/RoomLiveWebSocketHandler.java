package com.mahroosdev.voicelink.room;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RoomLiveWebSocketHandler extends TextWebSocketHandler {
    private final RoomLiveHub hub;
    private final ObjectMapper json;
    private final Map<String, RoomLiveHub.Connection> connections = new ConcurrentHashMap<>();

    public RoomLiveWebSocketHandler(RoomLiveHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        UUID roomId = (UUID) attributes.get(RoomLiveHandshakeInterceptor.ROOM_ID);
        UUID userId = (UUID) attributes.get(RoomLiveHandshakeInterceptor.USER_ID);
        HttpSession httpSession = (HttpSession) attributes.get(RoomLiveHandshakeInterceptor.HTTP_SESSION);
        if (roomId == null || userId == null || httpSession == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        var connection = hub.register(session, roomId, userId, httpSession);
        if (connection != null) connections.put(session.getId(), connection);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var connection = connections.get(session.getId());
        if (connection == null) return;
        String raw = message.getPayload();
        if (raw.getBytes(StandardCharsets.UTF_8).length > 8192) {
            hub.error(connection, "MESSAGE_TOO_LARGE", "Live message is too large.");
            return;
        }
        UUID clientMessageId;
        String text;
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isObject() || node.size() != 4
                    || !node.has("protocolVersion") || !node.get("protocolVersion").isInt()
                    || node.get("protocolVersion").intValue() != 1
                    || !node.has("type") || !node.get("type").isTextual()
                    || !"SEND_TEXT".equals(node.get("type").textValue())
                    || !node.has("clientMessageId") || !node.get("clientMessageId").isTextual()
                    || !node.has("text") || !node.get("text").isTextual()) {
                hub.error(connection, "INVALID_MESSAGE", "Invalid live message.");
                return;
            }
            clientMessageId = UUID.fromString(node.get("clientMessageId").textValue());
            text = node.get("text").textValue().strip();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > 1000) {
                hub.error(connection, "INVALID_MESSAGE", "Text must contain 1 to 1,000 characters.");
                return;
            }
        } catch (Exception ex) {
            hub.error(connection, "INVALID_MESSAGE", "Invalid live message.");
            return;
        }
        hub.sendText(connection, clientMessageId, text);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var connection = connections.get(session.getId());
        if (connection != null) hub.error(connection, "INVALID_MESSAGE", "Binary messages are unsupported.");
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (java.io.IOException ignored) {
            // Already disconnected.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var connection = connections.remove(session.getId());
        if (connection != null) hub.unregister(connection);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        var connection = connections.remove(session.getId());
        if (connection != null) hub.unregister(connection);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }
}
