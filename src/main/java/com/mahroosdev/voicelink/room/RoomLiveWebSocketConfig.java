package com.mahroosdev.voicelink.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RoomLiveWebSocketConfig implements WebSocketConfigurer {
    private final RoomLiveWebSocketHandler handler;
    private final RoomLiveHandshakeInterceptor handshake;

    public RoomLiveWebSocketConfig(RoomLiveWebSocketHandler handler,
                                   RoomLiveHandshakeInterceptor handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default allowed-origin policy is same-origin only.
        registry.addHandler(handler, "/ws/rooms/{roomId}").addInterceptors(handshake);
    }
}
