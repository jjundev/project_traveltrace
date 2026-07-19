package com.traveltrace.build;

import java.util.List;

/** Minimal projection of a Gemini models.list entry (GET /v1beta/models). */
public final class GeminiModel {
    public final String name;
    public final List<String> supportedGenerationMethods;

    public GeminiModel(String name, List<String> supportedGenerationMethods) {
        this.name = name;
        this.supportedGenerationMethods = supportedGenerationMethods;
    }
}
