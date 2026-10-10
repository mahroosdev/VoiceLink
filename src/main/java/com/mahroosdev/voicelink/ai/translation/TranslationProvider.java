package com.mahroosdev.voicelink.ai.translation;

import java.util.List;

public interface TranslationProvider {
    Result translate(TranslationRequest input);

    record TranslationRequest(String currentUtterance, String sourceLanguage, String targetLanguage,
                              List<ContextTurn> recentContext, List<GlossaryTerm> glossary) {
        public TranslationRequest {
            recentContext = List.copyOf(recentContext);
            glossary = List.copyOf(glossary);
        }
    }
    record ContextTurn(long turnIndex, String sourceLanguage, String targetLanguage,
                       String sourceTranscript) {}
    record GlossaryTerm(String sourceLanguage, String targetLanguage,
                        String sourceTerm, String preferredTerm) {}
    record Result(String translatedText, String providerId, String serviceId, long elapsedMillis) {}
}
