package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 스키마 위반·빈 응답은 <em>예외가 아니라 인식 실패</em>여야 한다(plan/08 DoD).
 * 실내·음식 사진에서 인식이 안 되는 건 오류가 아니라 정상 경로이며, 여기서 예외를
 * 던지면 그 사진 한 장이 배치 전체를 중단시킨다.
 */
@RunWith(RobolectricTestRunner.class)
public class VertexResponseParserTest {

    private static JsonObject wrap(String modelText) {
        JsonObject part = new JsonObject();
        part.addProperty("text", modelText);
        JsonObject content = new JsonObject();
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        parts.add(part);
        content.add("parts", parts);
        JsonObject candidate = new JsonObject();
        candidate.add("content", content);
        com.google.gson.JsonArray candidates = new com.google.gson.JsonArray();
        candidates.add(candidate);
        JsonObject root = new JsonObject();
        root.add("candidates", candidates);
        return root;
    }

    @Test
    public void parsesAFullyPopulatedResult() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":\"에펠탑\",\"city\":\"파리\",\"country\":\"프랑스\",\"confidence\":0.92}"));
        assertEquals("에펠탑", r.landmarkName);
        assertEquals("파리", r.city);
        assertEquals("프랑스", r.country);
        assertEquals(0.92d, r.confidence, 1e-9);
    }

    @Test
    public void nullNamesSurviveAsNull() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":null,\"city\":\"파리\",\"country\":null,\"confidence\":0.5}"));
        assertNull(r.landmarkName);
        assertEquals("파리", r.city);
        assertNull(r.country);
    }

    @Test
    public void stripsCodeFencesTheModelSometimesAdds() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "```json\n{\"city\":\"파리\",\"confidence\":0.7}\n```"));
        assertEquals("파리", r.city);
        assertEquals(0.7d, r.confidence, 1e-9);
    }

    @Test
    public void malformedJsonBecomesUnrecognizedNotAnException() {
        RecognitionResult r = VertexResponseParser.parse(wrap("{not json at all"));
        assertEquals(0d, r.confidence, 0d);
        assertNull(r.landmarkName);
    }

    @Test
    public void missingConfidenceBecomesUnrecognized() {
        RecognitionResult r = VertexResponseParser.parse(wrap("{\"city\":\"파리\"}"));
        assertEquals("스키마 위반은 인식 실패다 — 이름만 믿고 confidence 를 지어내지 않는다",
                0d, r.confidence, 0d);
    }

    @Test
    public void emptyCandidatesBecomesUnrecognized() {
        JsonObject root = JsonParser.parseString("{\"candidates\":[]}").getAsJsonObject();
        assertEquals(0d, VertexResponseParser.parse(root).confidence, 0d);
    }

    @Test
    public void safetyBlockedResponseBecomesUnrecognized() {
        JsonObject root = JsonParser.parseString(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").getAsJsonObject();
        assertEquals(0d, VertexResponseParser.parse(root).confidence, 0d);
    }

    @Test
    public void landmarkNameAsJsonObjectDoesNotThrow() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":{\"nested\":\"x\"},\"city\":\"파리\",\"confidence\":0.8}"));
        assertNull("객체가 온 landmarkName 은 부재로 취급해 null 이어야 한다", r.landmarkName);
        assertEquals("landmarkName 스키마 위반이 다른 필드 파싱을 막으면 안 된다", "파리", r.city);
        assertEquals("landmarkName 스키마 위반은 confidence 를 무너뜨리지 않는다", 0.8d, r.confidence, 1e-9);
    }

    @Test
    public void landmarkNameAsJsonArrayDoesNotThrow() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":[\"x\",\"y\"],\"city\":\"파리\",\"confidence\":0.8}"));
        assertNull("배열이 온 landmarkName 은 부재로 취급해 null 이어야 한다", r.landmarkName);
    }

    @Test
    public void candidateContentNotAnObjectBecomesUnrecognized() {
        JsonObject root = JsonParser.parseString(
                "{\"candidates\":[{\"content\":\"oops\"}]}").getAsJsonObject();
        RecognitionResult r = VertexResponseParser.parse(root);
        assertEquals("content 가 객체가 아니면 예외 대신 인식 실패로 수렴해야 한다", 0d, r.confidence, 0d);
    }
}
