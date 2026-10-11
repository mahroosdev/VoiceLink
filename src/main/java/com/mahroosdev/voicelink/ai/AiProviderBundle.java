package com.mahroosdev.voicelink.ai;

import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;

public record AiProviderBundle(SpeechToTextProvider stt, TranslationProvider translation,
                               TextToSpeechProvider tts) {}
