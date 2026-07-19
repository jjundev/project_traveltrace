package com.traveltrace.build;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class VisionModelResolverTest {

    @Test
    public void selectsNewestGeminiVisionModel() {
        String r = VisionModelResolver.selectGemini(Arrays.asList(
                new GeminiModel("models/gemini-1.5-pro", Arrays.asList("generateContent", "countTokens")),
                new GeminiModel("models/gemini-2.0-flash", Collections.singletonList("generateContent")),
                new GeminiModel("models/text-embedding-004", Collections.singletonList("embedContent"))
        ));
        assertEquals("gemini-2.0-flash", r);
    }

    @Test(expected = NoSuitableModelException.class)
    public void emptyGeminiThrows() {
        VisionModelResolver.selectGemini(Collections.emptyList());
    }

    @Test(expected = NoSuitableModelException.class)
    public void geminiWithoutGenerateContentThrows() {
        VisionModelResolver.selectGemini(Collections.singletonList(
                new GeminiModel("models/gemini-pro-vision", Collections.singletonList("countTokens"))));
    }

    @Test(expected = NoSuitableModelException.class)
    public void geminiEmbeddingExcluded() {
        VisionModelResolver.selectGemini(Collections.singletonList(
                new GeminiModel("models/gemini-embedding-001", Collections.singletonList("generateContent"))));
    }

    @Test
    public void selectsOpenAiVisionModel() {
        String r = VisionModelResolver.selectOpenAi(Arrays.asList(
                new OpenAiModel("gpt-3.5-turbo"),
                new OpenAiModel("gpt-4o"),
                new OpenAiModel("whisper-1")));
        assertEquals("gpt-4o", r);
    }

    @Test(expected = NoSuitableModelException.class)
    public void openAiWithoutVisionThrows() {
        VisionModelResolver.selectOpenAi(Arrays.asList(
                new OpenAiModel("whisper-1"),
                new OpenAiModel("text-embedding-3-small")));
    }

    @Test(expected = NoSuitableModelException.class)
    public void openAiEmptyThrows() {
        VisionModelResolver.selectOpenAi(Collections.emptyList());
    }

    @Test
    public void selectReturnsBoth() {
        String[] r = VisionModelResolver.select(
                Collections.singletonList(new GeminiModel("models/gemini-2.0-flash", Collections.singletonList("generateContent"))),
                Collections.singletonList(new OpenAiModel("gpt-4o")));
        assertEquals("gemini-2.0-flash", r[0]);
        assertEquals("gpt-4o", r[1]);
    }
}
