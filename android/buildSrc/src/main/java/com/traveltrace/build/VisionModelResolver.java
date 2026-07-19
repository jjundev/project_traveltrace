package com.traveltrace.build;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure selection logic — no network, no Gradle types — so it is unit-testable in
 * isolation (see VisionModelResolverTest).
 *
 * Design note (A2): the LIVE list proves existence / non-deprecation (a model that
 * disappears is simply absent — neither provider exposes a `deprecated` flag). VISION
 * CAPABILITY is decided here:
 *   - Gemini list carries supportedGenerationMethods, so we require generateContent plus
 *     a name filter (gemini-* family, excluding embedding/aqa/text-only names).
 *   - OpenAI /v1/models carries NO capability metadata, so capability is decided by a
 *     versioned allowlist of id patterns, intersected with the live list. Acknowledged
 *     limitation: a new vision model under an unanticipated name prefix is excluded until
 *     the allowlist is updated.
 */
public final class VisionModelResolver {
    private VisionModelResolver() {}

    /** OpenAI vision-capable id patterns (capability allowlist). */
    static final List<Pattern> OPENAI_VISION_ALLOWLIST = Arrays.asList(
            Pattern.compile("^gpt-4o(-.*)?$"),
            Pattern.compile("^gpt-4\\.1(-.*)?$"),
            Pattern.compile("^gpt-5(-.*)?$"),
            Pattern.compile("^o[1-9][0-9]*(-.*)?$")
    );

    private static final Pattern GEMINI_EXCLUDE =
            Pattern.compile("(embedding|aqa|gemma|text-bison|chat-bison)", Pattern.CASE_INSENSITIVE);

    private static String shortName(String name) {
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    public static String selectGemini(List<GeminiModel> models) {
        String best = models.stream()
                .filter(m -> m.supportedGenerationMethods != null
                        && m.supportedGenerationMethods.contains("generateContent"))
                .filter(m -> shortName(m.name).startsWith("gemini-"))
                .filter(m -> !GEMINI_EXCLUDE.matcher(m.name).find())
                .map(m -> shortName(m.name))
                .max(Comparator.naturalOrder())  // heuristic: lexicographically-newest name
                .orElse(null);
        if (best == null) {
            throw new NoSuitableModelException(
                    "Gemini: live model list has no entry satisfying the vision ruleset (rulesetVersion="
                            + Constants.RULESET_VERSION
                            + "). The list may be empty/unsuitable, or the ruleset needs updating.");
        }
        return best;
    }

    public static String selectOpenAi(List<OpenAiModel> models) {
        String best = models.stream()
                .filter(m -> OPENAI_VISION_ALLOWLIST.stream().anyMatch(p -> p.matcher(m.id).matches()))
                .map(m -> m.id)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (best == null) {
            throw new NoSuitableModelException(
                    "OpenAI: live model list does not intersect the vision allowlist (rulesetVersion="
                            + Constants.RULESET_VERSION
                            + "). Live models may exist but match no vision pattern — update the allowlist.");
        }
        return best;
    }

    /** Resolve both providers; throws NoSuitableModelException if either yields nothing. */
    public static String[] select(List<GeminiModel> gemini, List<OpenAiModel> openAi) {
        return new String[]{selectGemini(gemini), selectOpenAi(openAi)};
    }
}
