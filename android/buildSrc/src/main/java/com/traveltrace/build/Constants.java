package com.traveltrace.build;

/** Build-logic constants shared by the resolve task and guards. */
public final class Constants {
    private Constants() {}

    /**
     * Capability ruleset version. Bump whenever the selection logic / allowlists change.
     * The committed gradle/visionModels.generated.json records the version it was resolved
     * under; StalenessGuardTask hard-fails (on the `check` lifecycle) when they diverge.
     */
    public static final String RULESET_VERSION = "2026-01";
}
