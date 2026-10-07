package com.mahroosdev.voicelink.ai.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
public class AzureTranslationProvider implements TranslationProvider {
    private final StandardProviderSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;

    @Autowired
    public AzureTranslationProvider(StandardProviderSettings settings, ObjectMapper json) {
        this(settings, json, null);
    }

    AzureTranslationProvider(StandardProviderSettings settings, ObjectMapper json, HttpClient client) {
        this.settings = settings;
        this.json = json;
        this.client = client;
    }

    @Override
    public Result translate(Input input) {
        if (!(("en".equals(input.sourceLanguage()) && "ta".equals(input.targetLanguage()))
                || ("ta".equals(input.sourceLanguage()) && "en".equals(input.targetLanguage())))) {
            throw new ProviderFailure(ProviderFailure.Code.UNSUPPORTED_LANGUAGE);
        }
        if (input.transcript() == null || input.transcript().isBlank()
                || input.transcript().codePointCount(0, input.transcript().length()) > 4000) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
        if (!input.recentContext().isEmpty() || !input.glossary().isEmpty()) {
            throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        }
        String key = settings.translatorKey();
        String region = settings.translatorRegion();
        String endpoint = settings.translatorEndpoint();
        if (!endpoint.startsWith("https://") || endpoint.contains("?")) {
            throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        }
        long start = System.nanoTime();
        try {
            URI uri = URI.create(endpoint.replaceAll("/+$", "") + "/translate?api-version=3.0&from="
                    + input.sourceLanguage() + "&to=" + input.targetLanguage());
            String body = json.writeValueAsString(List.of(Map.of("Text", input.transcript())));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("Ocp-Apim-Subscription-Region", region)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            String response = ProviderHttp.send(client == null ? ProviderHttp.client() : client,
                    request, HttpResponse.BodyHandlers.ofString()).body();
            String text = parseTranslation(json, response, input.targetLanguage());
            return new Result(text, StandardProfile.TRANSLATION_PROVIDER,
                    StandardProfile.TRANSLATION_SERVICE,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (ProviderFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
    }

    static String parseTranslation(ObjectMapper json, String response, String targetLanguage) {
        try {
            JsonNode root = json.readTree(response);
            if (!root.isArray() || root.size() != 1) throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            JsonNode translations = root.get(0).path("translations");
            if (!translations.isArray() || translations.size() != 1
                    || !targetLanguage.equals(translations.get(0).path("to").asText())) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            String text = translations.get(0).path("text").asText("").strip();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > 6000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            return text;
        } catch (ProviderFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
        }
    }
}
