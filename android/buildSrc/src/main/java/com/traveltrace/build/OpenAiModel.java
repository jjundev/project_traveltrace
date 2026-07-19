package com.traveltrace.build;

/** Minimal projection of an OpenAI /v1/models entry (only id is exposed). */
public final class OpenAiModel {
    public final String id;

    public OpenAiModel(String id) {
        this.id = id;
    }
}
