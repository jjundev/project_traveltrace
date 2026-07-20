package com.traveltrace.build;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class VisionModelResolverTest {

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
                Collections.singletonList(vx("gemini-2.0-flash", "GA")),
                Collections.singletonList(new OpenAiModel("gpt-4o")));
        assertEquals("gemini-2.0-flash", r[0]);
        assertEquals("gpt-4o", r[1]);
    }

    private static VertexModel vx(String shortName, String stage) {
        return new VertexModel("publishers/google/models/" + shortName, stage);
    }

    @Test
    public void selectsLexicographicallyNewestVisionModel() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("gemini-2.5-flash", "GA"),
                vx("gemini-1.5-pro", "GA")));
        assertEquals("gemini-2.5-flash", picked);
    }

    @Test
    public void excludesDeprecatedEvenWhenItSortsNewest() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("gemini-9.9-flash", "DEPRECATED")));
        assertEquals("DEPRECATED 는 이름이 최신이어도 고르지 않는다",
                "gemini-2.0-flash", picked);
    }

    @Test
    public void excludesNonVisionFamilies() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("text-embedding-005", "GA"),
                vx("gemma-3-27b", "GA"),
                vx("gemini-embedding-001", "GA")));
        assertEquals("gemini-2.0-flash", picked);
    }

    @Test(expected = NoSuitableModelException.class)
    public void throwsWhenNothingMatchesTheRuleset() {
        VisionModelResolver.selectVertex(Collections.singletonList(
                vx("text-embedding-005", "GA")));
    }

    @Test(expected = NoSuitableModelException.class)
    public void throwsOnEmptyLiveList() {
        VisionModelResolver.selectVertex(Collections.<VertexModel>emptyList());
    }
}
