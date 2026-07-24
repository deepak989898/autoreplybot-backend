package com.autoreplybot;

import androidx.annotation.NonNull;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Local exact and practical semantic repetition guard for recent assistant replies. */
public final class ReplyRepetitionChecker {
    private static final Set<String> GREETINGS = new HashSet<>(Arrays.asList(
            "hi", "hii", "hello", "hey", "namaste", "namaskar", "dear",
            "good", "morning", "afternoon", "evening", "जी", "हाय", "नमस्ते"));

    public boolean isRepeated(@NonNull String candidate,
                              @NonNull Iterable<String> recentAssistantReplies) {
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.isEmpty()) return true;
        for (String previous : recentAssistantReplies) {
            String normalizedPrevious = normalize(previous == null ? "" : previous);
            if (normalizedPrevious.isEmpty()) continue;
            if (normalizedCandidate.equals(normalizedPrevious)) return true;
            if (semanticSimilarity(normalizedCandidate, normalizedPrevious) >= 0.78d) return true;
        }
        return false;
    }

    @NonNull
    static String normalize(@NonNull String text) {
        String source = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp)
                    || type == Character.NON_SPACING_MARK
                    || Character.isWhitespace(cp)) {
                cleaned.appendCodePoint(cp);
            } else {
                cleaned.append(' ');
            }
        }
        String[] words = cleaned.toString().trim().split("\\s+");
        int start = 0;
        while (start < words.length && GREETINGS.contains(words[start])) start++;
        StringBuilder result = new StringBuilder();
        for (int i = start; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(words[i]);
        }
        return result.toString();
    }

    private static double semanticSimilarity(String first, String second) {
        Set<String> a = shingles(first);
        Set<String> b = shingles(second);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        double jaccard = union.isEmpty() ? 0d
                : (double) intersection.size() / (double) union.size();
        int max = Math.max(first.length(), second.length());
        double editSimilarity = max == 0 ? 1d
                : 1d - ((double) levenshtein(first, second) / (double) max);
        return Math.max(jaccard, editSimilarity);
    }

    @NonNull
    private static Set<String> shingles(@NonNull String value) {
        String[] tokens = value.split("\\s+");
        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            if (!token.isEmpty()) result.add(token);
        }
        for (int i = 0; i + 1 < tokens.length; i++) {
            result.add(tokens[i] + " " + tokens[i + 1]);
        }
        return result;
    }

    private static int levenshtein(String first, String second) {
        int[] previous = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[second.length()];
    }
}
