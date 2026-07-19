package com.traveltrace.build;

import groovy.json.JsonSlurper;

import java.io.File;
import java.util.Map;

/**
 * Single source of truth for the CONFIGURATION-TIME read of the committed
 * gradle/visionModels.generated.json. app/build.gradle's readVisionModels() delegates
 * here so the missing-file / blank-id hard-fail contract lives in one testable place
 * (see VisionModelsFileTest) instead of being duplicated in inline Groovy.
 *
 * The exception is unchecked so it propagates out of Gradle configuration and aborts the
 * build exactly like the previous inline `throw new GradleException(...)` did — Gradle
 * turns any Throwable raised during configuration into a build failure. This is the
 * verified mechanism behind PRD §4.3 / DoD "유효 vision 모델이 없으면 빌드 실패".
 */
public final class VisionModelsFile {
    private VisionModelsFile() {}

    /** Thrown when the committed model file is absent or carries a blank model id. */
    public static final class InvalidVisionModelsFileException extends RuntimeException {
        public InvalidVisionModelsFileException(String message) {
            super(message);
        }
    }

    /** Parsed, validated result: both model ids are guaranteed non-blank. */
    public static final class Result {
        public final String gemini;
        public final String openAi;
        public final String rulesetVersion;

        public Result(String gemini, String openAi, String rulesetVersion) {
            this.gemini = gemini;
            this.openAi = openAi;
            this.rulesetVersion = rulesetVersion;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /**
     * Read + validate. Throws {@link InvalidVisionModelsFileException} when the file is
     * missing or either model id is blank — the two hard-fail cases from PRD §4.3.
     * The ruleset-version check is intentionally NOT enforced here (it is a config-time
     * WARNING and a check-lifecycle hard-fail via StalenessGuardTask); rulesetVersion is
     * returned raw so the caller can warn.
     */
    @SuppressWarnings("unchecked")
    public static Result read(File file) {
        if (file == null || !file.exists()) {
            throw new InvalidVisionModelsFileException(
                    "gradle/visionModels.generated.json missing — run ./gradlew resolveVisionModels first.");
        }
        Object root = new JsonSlurper().parse(file);
        if (!(root instanceof Map)) {
            throw new InvalidVisionModelsFileException(
                    "visionModels.generated.json is not a JSON object — re-run ./gradlew resolveVisionModels.");
        }
        Map<String, Object> map = (Map<String, Object>) root;
        String gemini = str(map.get("geminiModel"));
        String openAi = str(map.get("openAiModel"));
        if (gemini.isEmpty() || openAi.isEmpty()) {
            throw new InvalidVisionModelsFileException(
                    "visionModels.generated.json has blank model id(s) — re-run ./gradlew resolveVisionModels.");
        }
        return new Result(gemini, openAi, str(map.get("rulesetVersion")));
    }
}
