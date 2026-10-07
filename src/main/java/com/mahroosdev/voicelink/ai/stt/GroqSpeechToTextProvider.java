package com.mahroosdev.voicelink.ai.stt;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.ProviderHttp;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GroqSpeechToTextProvider implements SpeechToTextProvider {
    private static final URI ENDPOINT = URI.create("https://api.groq.com/openai/v1/audio/transcriptions");
    private final StandardProviderSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;

    @Autowired
    public GroqSpeechToTextProvider(StandardProviderSettings settings, ObjectMapper json) {
        this(settings, json, null);
    }

    GroqSpeechToTextProvider(StandardProviderSettings settings, ObjectMapper json, HttpClient client) {
        this.settings = settings;
        this.json = json;
        this.client = client;
    }

    @Override
    public Result transcribe(Input input) {
        if (!"en".equals(input.sourceLanguage()) && !"ta".equals(input.sourceLanguage())) {
            throw new ProviderFailure(ProviderFailure.Code.UNSUPPORTED_LANGUAGE);
        }
        String key = settings.groqKey();
        long start = System.nanoTime();
        String boundary = "voicelink-" + UUID.randomUUID();
        byte[] body = multipart(boundary, input.webmOpus(), input.sourceLanguage());
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        String response = ProviderHttp.send(client == null ? ProviderHttp.client() : client,
                request, HttpResponse.BodyHandlers.ofString()).body();
        return new Result(parseTranscript(json, response), StandardProfile.STT_PROVIDER,
                StandardProfile.STT_MODEL, Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    static String parseTranscript(ObjectMapper json, String response) {
        try {
            String text = json.readTree(response).path("text").asText("").strip();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > 4000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            return text;
        } catch (ProviderFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
    }

    private static byte[] multipart(String boundary, byte[] audio, String language) {
        if (audio == null || audio.length == 0 || audio.length > 1024 * 1024) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 1024);
        part(out, boundary, "model", StandardProfile.STT_MODEL);
        part(out, boundary, "language", language);
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"turn.webm\"\r\n"
                + "Content-Type: audio/webm\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(audio);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
