package com.traveltrace.build;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies DoD line 45 ("유효 vision 모델이 없을 때 빌드가 의도적으로 실패함을 검증") at the exact
 * code path the build runs: app/build.gradle's readVisionModels() delegates to
 * {@link VisionModelsFile#read(File)}, so exercising that method here is equivalent to
 * exercising the configuration-time hard-fail — any exception it raises aborts the build.
 */
public class VisionModelsFileTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File json(String content) throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void failsWhenFileMissing() {
        File missing = new File(tmp.getRoot(), "does-not-exist.json");
        try {
            VisionModelsFile.read(missing);
            fail("expected InvalidVisionModelsFileException for missing file");
        } catch (VisionModelsFile.InvalidVisionModelsFileException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void failsWhenBothModelIdsBlank() throws Exception {
        File f = json("{\"geminiModel\":\"\",\"openAiModel\":\"\",\"rulesetVersion\":\"2026-01\"}");
        try {
            VisionModelsFile.read(f);
            fail("expected InvalidVisionModelsFileException for blank ids");
        } catch (VisionModelsFile.InvalidVisionModelsFileException e) {
            assertTrue(e.getMessage().contains("blank model id"));
        }
    }

    @Test
    public void failsWhenOnlyOneModelIdPresent() throws Exception {
        File f = json("{\"geminiModel\":\"gemini-2.0-flash\",\"openAiModel\":\"\",\"rulesetVersion\":\"2026-01\"}");
        try {
            VisionModelsFile.read(f);
            fail("expected InvalidVisionModelsFileException when openAiModel blank");
        } catch (VisionModelsFile.InvalidVisionModelsFileException e) {
            assertTrue(e.getMessage().contains("blank model id"));
        }
    }

    @Test
    public void failsWhenNotJsonObject() throws Exception {
        File f = json("[\"not\",\"an\",\"object\"]");
        try {
            VisionModelsFile.read(f);
            fail("expected InvalidVisionModelsFileException for non-object json");
        } catch (VisionModelsFile.InvalidVisionModelsFileException e) {
            assertTrue(e.getMessage().contains("not a JSON object"));
        }
    }

    @Test
    public void succeedsWhenBothModelIdsPresent() throws Exception {
        File f = json("{\"geminiModel\":\"gemini-2.0-flash\",\"openAiModel\":\"gpt-4.1\",\"rulesetVersion\":\"2026-01\"}");
        VisionModelsFile.Result r = VisionModelsFile.read(f);
        assertEquals("gemini-2.0-flash", r.gemini);
        assertEquals("gpt-4.1", r.openAi);
        assertEquals("2026-01", r.rulesetVersion);
    }

    @Test
    public void trimsWhitespaceAroundModelIds() throws Exception {
        File f = json("{\"geminiModel\":\"  gemini-2.0-flash  \",\"openAiModel\":\"gpt-4.1\",\"rulesetVersion\":\"2026-01\"}");
        VisionModelsFile.Result r = VisionModelsFile.read(f);
        assertEquals("gemini-2.0-flash", r.gemini);
    }
}
