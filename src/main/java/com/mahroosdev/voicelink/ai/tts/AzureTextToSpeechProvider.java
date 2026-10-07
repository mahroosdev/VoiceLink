package com.mahroosdev.voicelink.ai.tts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.ProviderHttp;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AzureTextToSpeechProvider implements TextToSpeechProvider {
    private static final String FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    private static final Map<String, String> VOICES = Map.of(
            "en", "en-US-JennyNeural", "ta", "ta-LK-SaranyaNeural");
    private final StandardProviderSettings settings;
    private final HttpClient client;

    @Autowired
    public AzureTextToSpeechProvider(StandardProviderSettings settings) {
        this(settings, null);
    }

    AzureTextToSpeechProvider(StandardProviderSettings settings, HttpClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public Result synthesize(Input input) {
        String voice = VOICES.get(input.targetLanguage());
        if (voice == null) throw new ProviderFailure(ProviderFailure.Code.UNSUPPORTED_LANGUAGE);
        if (input.translatedText() == null || input.translatedText().isBlank()
                || input.translatedText().codePointCount(0, input.translatedText().length()) > 6000) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
        String key = settings.speechKey();
        String region = settings.speechRegion();
        if (!region.matches("[a-z0-9-]+")) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        String locale = "ta".equals(input.targetLanguage()) ? "ta-LK" : "en-US";
        String ssml = ssml(locale, voice, input.translatedText());
        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + region
                        + ".tts.speech.microsoft.com/cognitiveservices/v1"))
                .timeout(Duration.ofSeconds(15))
                .header("Ocp-Apim-Subscription-Key", key)
                .header("X-Microsoft-OutputFormat", FORMAT)
                .header("Content-Type", "application/ssml+xml; charset=UTF-8")
                .header("User-Agent", "VoiceLink")
                .POST(HttpRequest.BodyPublishers.ofString(ssml)).build();
        byte[] bytes = ProviderHttp.send(client == null ? ProviderHttp.client() : client,
                request, HttpResponse.BodyHandlers.ofByteArray()).body();
        if (bytes.length < 2 || bytes.length > 2 * 1024 * 1024 || !looksLikeMp3(bytes)) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
        return new Result(bytes, "audio/mpeg", StandardProfile.TTS_PROVIDER, voice,
                Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    static String ssml(String locale, String voice, String text) {
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\""
                + locale + "\"><voice name=\"" + voice + "\">" + escape(text)
                + "</voice></speak>";
    }

    private static boolean looksLikeMp3(byte[] bytes) {
        return (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
