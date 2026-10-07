package com.mahroosdev.voicelink.ai.tts;

public interface TextToSpeechProvider {
    Result synthesize(Input input);

    record Input(String translatedText, String targetLanguage) {}
    record Result(byte[] audio, String mediaType, String providerId, String voiceId, long elapsedMillis) {}
}
