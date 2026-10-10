package com.mahroosdev.voicelink.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;

final class RecentContext {
    private RecentContext() {}

    static List<TranslationProvider.ContextTurn> select(List<Candidate> prior, long currentIndex, Instant now) {
        List<Candidate> eligible = prior.stream().filter(turn -> turn.turnIndex < currentIndex
                && turn.translationSucceeded && turn.sourceTranscript != null
                && !turn.sourceTranscript.isBlank()
                && turn.sourceTranscript.codePointCount(0, turn.sourceTranscript.length()) <= 400
                && !turn.acceptedAt.isAfter(now)
                && !turn.acceptedAt.isBefore(now.minusSeconds(300)))
                .sorted(java.util.Comparator.comparingLong(Candidate::turnIndex)).toList();
        List<TranslationProvider.ContextTurn> selected = new ArrayList<>();
        int total = 0;
        for (int i = eligible.size() - 1; i >= 0 && selected.size() < 3; i--) {
            Candidate turn = eligible.get(i);
            int length = turn.sourceTranscript.codePointCount(0, turn.sourceTranscript.length());
            if (total + length > 1200) continue;
            selected.add(new TranslationProvider.ContextTurn(turn.turnIndex, turn.sourceLanguage,
                    turn.targetLanguage, turn.sourceTranscript));
            total += length;
        }
        java.util.Collections.reverse(selected);
        return List.copyOf(selected);
    }

    record Candidate(long turnIndex, Instant acceptedAt, String sourceLanguage,
                     String targetLanguage, String sourceTranscript, boolean translationSucceeded) {}
}
