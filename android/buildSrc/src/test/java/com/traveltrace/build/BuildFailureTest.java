package com.traveltrace.build;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the Gradle-task-level failure wiring (not just the pure selector): the guard
 * tasks actually throw GradleException so the build aborts. Uses ProjectBuilder to
 * instantiate real tasks and invoke their actions.
 */
public class BuildFailureTest {

    @Test
    public void modelIdGuardFailsOnHardcodedLiteral() throws Exception {
        Project project = ProjectBuilder.builder().build();
        File srcDir = new File(project.getProjectDir(), "src/main/java/x");
        srcDir.mkdirs();
        Files.write(new File(srcDir, "Bad.java").toPath(),
                "String m = \"gpt-4o\";\n".getBytes(StandardCharsets.UTF_8));

        ModelIdGuardTask task = project.getTasks().create("guard", ModelIdGuardTask.class);
        task.getSources().from(project.fileTree(new File(project.getProjectDir(), "src/main")));

        try {
            task.check();
            fail("expected GradleException for hardcoded model id");
        } catch (GradleException e) {
            assertTrue(e.getMessage().contains("Hardcoded"));
        }
    }

    @Test
    public void modelIdGuardPassesOnCleanSourceWithDecoyTokens() throws Exception {
        Project project = ProjectBuilder.builder().build();
        File srcDir = new File(project.getProjectDir(), "src/main/java/x");
        srcDir.mkdirs();
        // decoys that the old `o\d` pattern would have falsely flagged:
        Files.write(new File(srcDir, "Good.java").toPath(),
                ("String model = BuildConfig.GEMINI_VISION_MODEL;\n"
                        + "int room2 = 1;\nint photo1 = 2;\nString iso = \"iso8601\";\nint to0 = 3;\n")
                        .getBytes(StandardCharsets.UTF_8));

        ModelIdGuardTask task = project.getTasks().create("guard", ModelIdGuardTask.class);
        task.getSources().from(project.fileTree(new File(project.getProjectDir(), "src/main")));

        task.check(); // must NOT throw
    }

    @Test
    public void stalenessFailsOnMissingFile() throws Exception {
        Project project = ProjectBuilder.builder().build();
        StalenessGuardTask task = project.getTasks().create("stale", StalenessGuardTask.class);
        task.getGeneratedFile().set(new File(project.getProjectDir(), "nope.json"));
        task.getExpectedRulesetVersion().set("2026-01");
        task.getMaxAgeDays().set(30L);

        try {
            task.check();
            fail("expected GradleException for missing generated file");
        } catch (GradleException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void stalenessFailsOnRulesetMismatch() throws Exception {
        Project project = ProjectBuilder.builder().build();
        File f = new File(project.getProjectDir(), "gen.json");
        Files.write(f.toPath(),
                "{\"geminiModel\":\"g\",\"openAiModel\":\"o\",\"rulesetVersion\":\"OLD\",\"resolvedAtIso\":\"2026-01-01T00:00:00Z\"}"
                        .getBytes(StandardCharsets.UTF_8));
        StalenessGuardTask task = project.getTasks().create("stale", StalenessGuardTask.class);
        task.getGeneratedFile().set(f);
        task.getExpectedRulesetVersion().set("2026-01");
        task.getMaxAgeDays().set(30L);

        try {
            task.check();
            fail("expected GradleException for ruleset mismatch");
        } catch (GradleException e) {
            assertTrue(e.getMessage().contains("ruleset mismatch"));
        }
    }
}
