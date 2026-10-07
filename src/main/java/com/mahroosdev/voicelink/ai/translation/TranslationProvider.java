package com.mahroosdev.voicelink.ai.translation;

import java.util.List;
import java.util.Map;

public interface TranslationProvider {
    Result translate(Input input);

    record Input(String transcript, String sourceLanguage, String targetLanguage,
                 List<String> recentContext, Map<String, String> glossary) {}
    record Result(String translatedText, String providerId, String serviceId, long elapsedMillis) {}
}
