package com.mahroosdev.voicelink.room;

import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class RoomLiveHandshakeInterceptor implements HandshakeInterceptor {
    static final String ROOM_ID = "roomId";
    static final String USER_ID = "userId";
    static final String HTTP_SESSION = "httpSession";
    private static final String PREFIX = "/ws/rooms/";

    private final RoomLiveAuthorizer authorizer;

    public RoomLiveHandshakeInterceptor(RoomLiveAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)
                || !(request.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || !authentication.isAuthenticated()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        HttpSession httpSession = servletRequest.getServletRequest().getSession(false);
        if (httpSession == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String path = request.getURI().getPath();
        if (!path.startsWith(PREFIX)) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        UUID roomId;
        try {
            roomId = UUID.fromString(path.substring(PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        try {
            var member = authorizer.authorize(principal.getUserId(), roomId);
            if (member.status() == RoomStatus.CLOSED) {
                response.setStatusCode(HttpStatus.CONFLICT);
                return false;
            }
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        attributes.put(ROOM_ID, roomId);
        attributes.put(USER_ID, principal.getUserId());
        attributes.put(HTTP_SESSION, httpSession);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {}
}
