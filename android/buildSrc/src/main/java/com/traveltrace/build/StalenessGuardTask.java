package com.traveltrace.build;

import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Guards the committed vision-model resolution. Wired into the `check` lifecycle, which
 * CI / release pipelines run.
 *
 *   - rulesetVersion mismatch -> HARD FAILURE (selection logic changed but file not
 *     regenerated). Since this runs on `check`, debug assembleDebug only sees the
 *     configuration-time WARNING while release pipelines (which run `check`) fail.
 *   - age beyond maxAgeDays -> WARNING ONLY (even in release), so a clean checkout of an
 *     unchanged committed file always builds reproducibly.
 */
public abstract class StalenessGuardTask extends DefaultTask {

    @Internal
    public abstract RegularFileProperty getGeneratedFile();

    @Input
    public abstract Property<String> getExpectedRulesetVersion();

    @Input
    public abstract Property<Long> getMaxAgeDays();

    @SuppressWarnings("unchecked")
    @TaskAction
    public void check() throws Exception {
        File f = getGeneratedFile().get().getAsFile();
        if (!f.exists()) {
            throw new GradleException("gradle/visionModels.generated.json missing — run ./gradlew resolveVisionModels first.");
        }
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> json = (Map<String, Object>) new JsonSlurper().parseText(text);

        Object fileRuleset = json.get("rulesetVersion");
        String expected = getExpectedRulesetVersion().get();
        if (fileRuleset == null || !fileRuleset.equals(expected)) {
            throw new GradleException("Vision model ruleset mismatch: generated file=" + fileRuleset
                    + ", code=" + expected + ". Re-run ./gradlew resolveVisionModels.");
        }

        Object resolvedAt = json.get("resolvedAtIso");
        if (resolvedAt != null) {
            try {
                Instant ts = OffsetDateTime.parse(resolvedAt.toString()).toInstant();
                long ageDays = Duration.between(ts, Instant.now()).toDays();
                if (ageDays > getMaxAgeDays().get()) {
                    getLogger().warn("WARNING: vision model resolution is " + ageDays + " days old (max "
                            + getMaxAgeDays().get() + "). Consider ./gradlew resolveVisionModels.");
                }
            } catch (Exception e) {
                getLogger().warn("Could not parse resolvedAtIso ('" + resolvedAt + "'): " + e.getMessage());
            }
        }
    }
}
