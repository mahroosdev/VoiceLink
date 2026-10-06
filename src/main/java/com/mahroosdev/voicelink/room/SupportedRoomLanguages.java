package com.mahroosdev.voicelink.room;

import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class SupportedRoomLanguages {
    public Direction validate(String speaking, String listening) {
        String source = normalize(speaking);
        String target = normalize(listening);
        if (!(source.equals("en") && target.equals("ta")
                || source.equals("ta") && target.equals("en"))) {
            throw new IllegalArgumentException("Choose English to Tamil or Tamil to English.");
        }
        return new Direction(source, target);
    }

    private String normalize(String tag) {
        return tag == null ? "" : tag.strip().toLowerCase(Locale.ROOT);
    }

    public record Direction(String speaking, String listening) {
        public Direction reverse() { return new Direction(listening, speaking); }
    }
}
