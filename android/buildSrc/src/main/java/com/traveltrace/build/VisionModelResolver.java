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
 *   - Vertex publisherModels.list carries NO capability metadata (supportedActions is a
 *     console CallToAction, not a generateContent flag), so capability is decided by a
 *     versioned name allowlist. launchStage=DEPRECATED entries are dropped.
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

    /**
     * Vertex vision-capable name patterns (capability allowlist). Vertex's model list has
     * no capability metadata, so — exactly as with OpenAI — capability is a versioned
     * ruleset intersected with the live list. Acknowledged limitation: a new vision model
     * under an unanticipated name is excluded until this list is updated (RULESET_VERSION).
     */
    static final List<Pattern> VERTEX_VISION_ALLOWLIST = Arrays.asList(
            Pattern.compile("^gemini-[0-9].*$")
    );

    /** Names that match the allowlist prefix but are not vision chat models. */
    private static final Pattern VERTEX_EXCLUDE =
            Pattern.compile("(embedding|aqa|gemma|tts|image|veo|imagen)", Pattern.CASE_INSENSITIVE);

    private static final String DEPRECATED = "DEPRECATED";

    private static String shortName(String name) {
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    public static String selectVertex(List<VertexModel> models) {
        String best = models.stream()
                .filter(m -> !DEPRECATED.equalsIgnoreCase(m.launchStage))
                .map(m -> shortName(m.name))
                .filter(n -> VERTEX_VISION_ALLOWLIST.stream().anyMatch(p -> p.matcher(n).matches()))
                .filter(n -> !VERTEX_EXCLUDE.matcher(n).find())
                .max(Comparator.naturalOrder())  // heuristic: lexicographically-newest name
                .orElse(null);
        if (best == null) {
            throw new NoSuitableModelException(
                    "Vertex: live publisher model list has no entry satisfying the vision ruleset"
                            + " (rulesetVersion=" + Constants.RULESET_VERSION
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
    public static String[] select(List<VertexModel> vertex, List<OpenAiModel> openAi) {
        return new String[]{selectVertex(vertex), selectOpenAi(openAi)};
    }
}
