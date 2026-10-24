package com.mahroosdev.voicelink.room;

import java.text.Normalizer;

public final class ConversationDisplayName {
    public static final int MAX_CODE_POINTS = 48;
    public static final String VALIDATION_MESSAGE =
            "Use 1 to 48 Unicode characters without line breaks, control characters, or formatting controls.";

    private ConversationDisplayName() {}

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException(VALIDATION_MESSAGE);
        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_CODE_POINTS
                || normalized.codePoints().anyMatch(ConversationDisplayName::unsafeCodePoint)) {
            throw new IllegalArgumentException(VALIDATION_MESSAGE);
        }
        return normalized;
    }

    private static boolean unsafeCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE;
    }

    public static String resolve(RoomParticipant participant) {
        String roomName = participant.getConversationDisplayName();
        return roomName == null || roomName.isBlank()
                ? participant.getUser().getDisplayName()
                : roomName;
    }
}
