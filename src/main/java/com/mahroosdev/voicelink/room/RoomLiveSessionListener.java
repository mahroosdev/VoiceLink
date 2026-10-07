package com.mahroosdev.voicelink.room;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoomLiveSessionListener {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> liveSessionRevocation(RoomLiveHub hub) {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                hub.revokeHttpSession(event.getSession().getId());
            }
        });
    }
}
