package com.mahroosdev.voicelink.ai.tts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.ProviderHttp;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GeminiTextToSpeechProvider implements TextToSpeechProvider {
    private static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");
    private static final String VOICE = "Kore";
    private static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;
    private final StandardProviderSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;

    @Autowired
    public GeminiTextToSpeechProvider(StandardProviderSettings settings, ObjectMapper json) {
        this(settings, json, null);
    }

    GeminiTextToSpeechProvider(StandardProviderSettings settings, ObjectMapper json, HttpClient client) {
        this.settings = settings;
        this.json = json;
        this.client = client;
    }

    @Override
    public Result synthesize(Input input) {
        if (!("en".equals(input.targetLanguage()) || "ta".equals(input.targetLanguage()))) {
            throw new ProviderFailure(ProviderFailure.Code.UNSUPPORTED_LANGUAGE);
        }
        if (input.translatedText() == null || input.translatedText().isBlank()
                || input.translatedText().codePointCount(0, input.translatedText().length()) > 6000) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
        String key = settings.geminiKey();
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(15))
                    .header("x-goog-api-key", key)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(json, input))).build();
            String response = ProviderHttp.send(client == null ? ProviderHttp.client() : client,
                    request, HttpResponse.BodyHandlers.ofString()).body();
            byte[] audio = parseAudio(json, response);
            return new Result(audio, "audio/wav", StandardProfile.TTS_PROVIDER, VOICE,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (ProviderFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
    }

    static String requestBody(ObjectMapper json, Input input) {
        return json.writeValueAsString(Map.of(
                "model", StandardProfile.TTS_SERVICE,
                "input", List.of(Map.of("type", "user_input", "content",
                        List.of(Map.of("type", "text", "text", input.translatedText())))),
                "response_format", Map.of("type", "audio", "mime_type", "audio/wav"),
                "generation_config", Map.of("speech_config", List.of(Map.of("voice", VOICE)))));
    }

    static byte[] parseAudio(ObjectMapper json, String response) {
        try {
            JsonNode steps = json.readTree(response).path("steps");
            if (!steps.isArray()) throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            JsonNode audio = null;
            for (JsonNode step : steps) {
                if (!"model_output".equals(step.path("type").asText())) continue;
                JsonNode content = step.path("content");
                if (!content.isArray()) continue;
                for (JsonNode part : content) {
                    if ("audio".equals(part.path("type").asText())) audio = part;
                }
            }
            if (audio == null) throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            String mediaType = audio.path("mime_type").asText("audio/wav");
            String encoded = audio.path("data").asText("");
            if (!"audio/wav".equals(mediaType) || encoded.isEmpty()
                    || encoded.length() > ((MAX_AUDIO_BYTES + 2L) / 3L) * 4L) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 44 || bytes.length > MAX_AUDIO_BYTES || !looksLikeWav(bytes)) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            return bytes;
        } catch (ProviderFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
    }

    private static boolean looksLikeWav(byte[] bytes) {
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }
}
