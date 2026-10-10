package com.mahroosdev.voicelink.glossary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class GlossaryTerms {
    private GlossaryTerms() {}

    static String clean(String input, int max, boolean mayBeBlank) {
        if (input == null || input.length() > 512 || input.codePoints().anyMatch(Character::isISOControl)
                || input.codePoints().anyMatch(cp -> Character.getType(cp) == Character.SURROGATE)) {
            throw invalid();
        }
        StringBuilder compact = new StringBuilder();
        boolean space = false;
        for (int cp : Normalizer.normalize(input, Normalizer.Form.NFC).codePoints().toArray()) {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = compact.length() > 0;
            } else {
                if (space) compact.append(' ');
                compact.appendCodePoint(cp);
                space = false;
            }
        }
        String value = compact.toString();
        int length = value.codePointCount(0, value.length());
        if (length > max || (!mayBeBlank && length == 0)) throw invalid();
        return value;
    }

    static String lookup(String source) {
        return source.toLowerCase(Locale.ROOT);
    }

    static List<TranslationProvider.GlossaryTerm> applicable(
            List<RoomGlossaryEntry> entries, String source, String target, String utterance) {
        String text = Normalizer.normalize(utterance, Normalizer.Form.NFC);
        List<Match> candidates = new ArrayList<>();
        for (RoomGlossaryEntry entry : entries) {
            if (!entry.getSourceLanguage().equals(source) || !entry.getTargetLanguage().equals(target)) continue;
            Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(entry.getSourceTerm())
                    + "(?![\\p{L}\\p{M}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) candidates.add(new Match(entry, matcher.start(), matcher.end()));
        }
        candidates.sort(Comparator.<Match>comparingInt(match -> match.end - match.start).reversed()
                .thenComparingInt(match -> match.start));
        List<Match> chosen = new ArrayList<>();
        for (Match candidate : candidates) {
            if (chosen.stream().noneMatch(other -> candidate.start < other.end && other.start < candidate.end)) {
                chosen.add(candidate);
            }
        }
        chosen.sort(Comparator.comparingInt(match -> match.start));
        return chosen.stream().map(match -> new TranslationProvider.GlossaryTerm(source, target,
                match.entry.getSourceTerm(), match.entry.getPreferredTerm())).distinct().toList();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid glossary term");
    }

    private record Match(RoomGlossaryEntry entry, int start, int end) {}
}
