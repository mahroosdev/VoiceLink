package com.mahroosdev.voicelink.ai.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GeminiTextToSpeechProviderTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void constructsEnglishAndTamilVerbatimAudioRequestsWithDocumentedVoice() {
        for (String language : List.of("en", "ta")) {
            String text = "en".equals(language) ? "Hello" : "வணக்கம்";
            var body = json.readTree(GeminiTextToSpeechProvider.requestBody(json,
                    new TextToSpeechProvider.Input(text, language)));
            assertThat(body.path("model").asText()).isEqualTo("gemini-3.8-flash-lite-tts");
            assertThat(body.path("input").get(0).path("content").get(0).path("text").asText()).isEqualTo(text);
            assertThat(body.path("response_format").path("mime_type").asText()).isEqualTo("audio/wav");
            assertThat(body.path("generation_config").path("speech_config").get(0).path("voice").asText())
                    .isEqualTo("Kore");
            assertThat(body.toString()).doesNotContain("test-secret");
        }
        assertThat(StandardProfile.TTS_SERVICE).isEqualTo("gemini-3.8-flash-lite-tts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesWavAndRejectsMissingMalformedOrWrongMedia() throws Exception {
        byte[] wav = new byte[44];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        String encoded = Base64.getEncoder().encodeToString(wav);
        String good = "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\""
                + encoded + "\"}]}]}";
        assertThat(GeminiTextToSpeechProvider.parseAudio(json, good)).isEqualTo(wav);
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(good);
        var provider = new GeminiTextToSpeechProvider(new StandardProviderSettings(true, "unused", "test-secret"),
                json, http);
        var result = provider.synthesize(new TextToSpeechProvider.Input("வணக்கம்", "ta"));
        assertThat(result.mediaType()).isEqualTo("audio/wav");
        assertThat(result.audio()).isEqualTo(wav);
        assertThat(result.voiceId()).isEqualTo("Kore");
        for (String bad : List.of("{}", "not json", good.replace("audio/wav", "audio/mpeg"),
                good.replace(encoded, "invalid-base64"), good.replace("model_output", "user_input"))) {
            assertThatThrownBy(() -> GeminiTextToSpeechProvider.parseAudio(json, bad))
                    .isInstanceOfSatisfying(ProviderFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo(ProviderFailure.Code.INVALID_RESPONSE));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsAuthQuotaTimeoutWithoutLeakingKeyOrSpeechText() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.body()).thenReturn("private provider response");
        var provider = new GeminiTextToSpeechProvider(new StandardProviderSettings(true, "unused", "test-secret"),
                json, http);
        var input = new TextToSpeechProvider.Input("private speech text", "en");
        for (var caseEntry : Map.of(401, ProviderFailure.Code.CONFIGURATION,
                403, ProviderFailure.Code.CONFIGURATION, 429, ProviderFailure.Code.RATE_LIMIT,
                503, ProviderFailure.Code.UNAVAILABLE).entrySet()) {
            when(response.statusCode()).thenReturn(caseEntry.getKey());
            assertThatThrownBy(() -> provider.synthesize(input)).isInstanceOfSatisfying(ProviderFailure.class,
                    failure -> {
                        assertThat(failure.code()).isEqualTo(caseEntry.getValue());
                        assertThat(failure.getMessage()).doesNotContain("test-secret", "private provider", "private speech");
                    });
        }
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpTimeoutException("private provider response"));
        assertThatThrownBy(() -> provider.synthesize(input)).isInstanceOfSatisfying(ProviderFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(ProviderFailure.Code.TIMEOUT));
    }
}
