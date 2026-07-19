package com.traveltrace.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A2 dead-ID guard. Fails the build if a model-ID string LITERAL is hardcoded in app
 * sources — the resolved IDs must come from BuildConfig.GEMINI_VISION_MODEL /
 * OPENAI_VISION_MODEL only.
 *
 * Patterns are anchored to an opening quote so they match only string literals shaped
 * like model ids — NOT ordinary identifiers (room2, photo1, iso8601 ... are safe). The
 * caller passes only app/src/main sources and excludes generated BuildConfig + test
 * fixtures, so the selector's unit-test data never trips the guard.
 */
public abstract class ModelIdGuardTask extends DefaultTask {

    @InputFiles
    public abstract ConfigurableFileCollection getSources();

    private static final List<Pattern> PATTERNS = Arrays.asList(
            Pattern.compile("\"gpt-[0-9]"),
            Pattern.compile("\"gemini-[0-9]"),
            Pattern.compile("\"o[1-9][0-9]*(-[a-z]+)?\"")
    );

    @TaskAction
    public void check() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (File f : getSources().getFiles()) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if (!(n.endsWith(".java") || n.endsWith(".xml"))) continue;
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (Pattern p : PATTERNS) {
                    if (p.matcher(line).find()) {
                        offenders.add(f.getPath() + ":" + (i + 1) + ": " + line.trim());
                        break;
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new GradleException("Hardcoded model-ID literal(s) detected. Use "
                    + "BuildConfig.GEMINI_VISION_MODEL / BuildConfig.OPENAI_VISION_MODEL instead:\n"
                    + String.join("\n", offenders));
        }
    }
}
