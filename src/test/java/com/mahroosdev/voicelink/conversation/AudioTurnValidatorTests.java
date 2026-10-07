package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AudioTurnValidatorTests {
    private final AudioTurnValidator validator = new AudioTurnValidator();

    static byte[] sample() {
        byte[] data = new byte[64];
        data[0] = 0x1a; data[1] = 0x45; data[2] = (byte) 0xdf; data[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, data, 8, marker.length);
        return data;
    }

    @Test void acceptsShortWebmOpusSignature() {
        assertThatCode(() -> validator.validate(sample(), "audio/webm;codecs=opus", 1000))
                .doesNotThrowAnyException();
    }

    @Test void rejectsEmptyOversizedWrongFormatAndDuration() {
        assertThatThrownBy(() -> validator.validate(new byte[0], "audio/webm;codecs=opus", 1000))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate(new byte[AudioTurnValidator.MAX_BYTES + 1],
                "audio/webm;codecs=opus", 1000)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate(sample(), "audio/wav", 1000))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate(sample(), "audio/webm;codecs=opus", 16000))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsMissingWebmOrOpusMarker() {
        byte[] badSignature = sample();
        badSignature[0] = 0;
        assertThatThrownBy(() -> validator.validate(badSignature, "audio/webm;codecs=opus", 1000))
                .isInstanceOf(ResponseStatusException.class);
        byte[] invalid = sample();
        invalid[8] = 0;
        assertThatThrownBy(() -> validator.validate(invalid, "audio/webm;codecs=opus", 1000))
                .isInstanceOf(ResponseStatusException.class);
    }
}
