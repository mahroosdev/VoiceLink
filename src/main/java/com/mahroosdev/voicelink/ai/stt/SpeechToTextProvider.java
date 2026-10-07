package com.mahroosdev.voicelink.ai.stt;

public interface SpeechToTextProvider {
    Result transcribe(Input input);

    record Input(byte[] webmOpus, String sourceLanguage) {}
    record Result(String transcript, String providerId, String modelId, long elapsedMillis) {}
}
