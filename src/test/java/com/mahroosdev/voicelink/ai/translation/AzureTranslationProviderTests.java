package com.mahroosdev.voicelink.ai.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AzureTranslationProviderTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void parsesSyntheticTranslationAndRejectsWrongTarget() {
        String response = "[{\"translations\":[{\"text\":\"வணக்கம்\",\"to\":\"ta\"}]}]";
        assertThat(AzureTranslationProvider.parseTranslation(json, response, "ta"))
                .isEqualTo("வணக்கம்");
        assertThatThrownBy(() -> AzureTranslationProvider.parseTranslation(json, response, "en"))
                .isInstanceOf(ProviderFailure.class);
        assertThatThrownBy(() -> AzureTranslationProvider.parseTranslation(json, "[]", "ta"))
                .isInstanceOf(ProviderFailure.class);
    }
}
