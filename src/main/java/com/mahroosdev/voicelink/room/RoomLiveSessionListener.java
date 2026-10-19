package com.mahroosdev.voicelink.room;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import com.mahroosdev.voicelink.auth.AccountSessionRegistry;
import com.mahroosdev.voicelink.conversation.SpeechTurnService;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoomLiveSessionListener {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> liveSessionRevocation(RoomLiveHub hub,
            AccountSessionRegistry sessions, SpeechTurnService speechTurns) {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                sessions.remove(event.getSession());
                hub.revokeHttpSession(event.getSession().getId());
                speechTurns.revokeHttpSession(event.getSession().getId());
            }
        });
    }
}
