package com.mahroosdev.voicelink.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class GlossaryTermsTests {
    private RoomGlossaryEntry term(String sourceLanguage, String targetLanguage, String source) {
        return new RoomGlossaryEntry(null, null, sourceLanguage, targetLanguage,
                source, GlossaryTerms.lookup(source), "preferred " + source, Instant.EPOCH);
    }

    @Test void selectsOnlyDirectionWholePhrasesAndLongerOverlaps() {
        var entries = List.of(term("en", "ta", "API"), term("en", "ta", "REST API"),
                term("ta", "en", "API"));
        var selected = GlossaryTerms.applicable(entries, "en", "ta", "The rest api works; apiculture does not.");
        assertThat(selected).extracting(item -> item.sourceTerm()).containsExactly("REST API");
        assertThat(GlossaryTerms.applicable(entries, "en", "ta", "An API and another REST API work."))
                .extracting(item -> item.sourceTerm()).containsExactly("API", "REST API");
        assertThat(GlossaryTerms.applicable(entries, "en", "ta", "apiculture only")).isEmpty();
        assertThat(GlossaryTerms.applicable(entries, "ta", "en", "API"))
                .extracting(item -> item.sourceLanguage()).containsExactly("ta");
    }

    @Test void normalizationCollapsesWhitespaceAndUsesNfc() {
        assertThat(GlossaryTerms.clean("  Cafe\u0301   API  ", 48, false)).isEqualTo("Café API");
        assertThat(GlossaryTerms.lookup("REST API")).isEqualTo("rest api");
    }

    @Test void tamilCombiningMarkDoesNotCreateFalseWholeWordMatch() {
        var tamil = List.of(term("ta", "en", "க"));
        assertThat(GlossaryTerms.applicable(tamil, "ta", "en", "கா")).isEmpty();
        assertThat(GlossaryTerms.applicable(tamil, "ta", "en", "க நல்லது"))
                .extracting(item -> item.sourceTerm()).containsExactly("க");
    }
}
