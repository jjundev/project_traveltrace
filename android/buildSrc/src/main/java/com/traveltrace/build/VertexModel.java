package com.traveltrace.build;

/**
 * Minimal projection of a Vertex AI publisherModels.list entry
 * (GET https://aiplatform.googleapis.com/v1/publishers/google/models).
 *
 * Note what is NOT here: the Developer API's `supportedGenerationMethods`. Vertex's
 * response carries `supportedActions`, a console-UI CallToAction object that does not
 * state whether the model answers `:generateContent`. Vision capability is therefore
 * decided by the versioned name allowlist in VisionModelResolver, exactly as it already
 * is for OpenAI. `launchStage` is the one real signal Vertex adds — it lets us drop
 * DEPRECATED entries, which the Developer API never exposed.
 */
public final class VertexModel {
    /** Fully qualified, e.g. "publishers/google/models/gemini-2.5-flash". */
    public final String name;
    /** "GA" | "PUBLIC_PREVIEW" | "EXPERIMENTAL" | "DEPRECATED" | null when absent. */
    public final String launchStage;

    public VertexModel(String name, String launchStage) {
        this.name = name;
        this.launchStage = launchStage;
    }
}
