package com.mahroosdev.voicelink.conversation;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AudioTurnValidator {
    public static final int MAX_BYTES = 1024 * 1024;

    public void validate(byte[] bytes, String mediaType, long declaredDurationMillis) {
        if (bytes == null || bytes.length < 32 || bytes.length > MAX_BYTES) invalid();
        if (!"audio/webm;codecs=opus".equalsIgnoreCase(mediaType)) invalid();
        if (declaredDurationMillis < 100 || declaredDurationMillis > 15_000) invalid();
        if ((bytes[0] & 255) != 0x1a || (bytes[1] & 255) != 0x45
                || (bytes[2] & 255) != 0xdf || (bytes[3] & 255) != 0xa3) invalid();
        String header = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        if (!header.contains("webm") || !(header.contains("A_OPUS") || header.contains("OpusHead"))) invalid();
        double duration = declaredWebmDuration(bytes);
        if (duration > 15.5) invalid();
    }

    // MediaRecorder may omit Segment Info Duration. When present, reject an excessive value.
    private static double declaredWebmDuration(byte[] data) {
        int limit = Math.min(data.length - 10, 4096);
        for (int i = 4; i < limit; i++) {
            if ((data[i] & 255) != 0x44 || (data[i + 1] & 255) != 0x89) continue;
            int size = data[i + 2] & 255;
            if (size == 0x84 && i + 7 < data.length) {
                int bits = ((data[i + 3] & 255) << 24) | ((data[i + 4] & 255) << 16)
                        | ((data[i + 5] & 255) << 8) | (data[i + 6] & 255);
                return Float.intBitsToFloat(bits) / 1000.0;
            }
            if (size == 0x88 && i + 10 < data.length) {
                long bits = 0;
                for (int j = 3; j <= 10; j++) bits = (bits << 8) | (data[i + j] & 255);
                return Double.longBitsToDouble(bits) / 1000.0;
            }
        }
        return 0;
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid short WebM/Opus recording");
    }
}
