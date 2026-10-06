package com.mahroosdev.voicelink.room;

import java.security.SecureRandom;
import java.util.Locale;

final class RoomCode {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomCode() {}

    static String generate() {
        StringBuilder code = new StringBuilder(16);
        for (int i = 0; i < 16; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }

    static String canonicalize(String input) {
        if (input == null || input.length() > 32) return null;
        String code = input.strip().toUpperCase(Locale.ROOT).replace("-", "");
        if (code.length() != 16) return null;
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) return null;
        }
        return code;
    }

    static String display(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-"
                + code.substring(8, 12) + "-" + code.substring(12);
    }
}
