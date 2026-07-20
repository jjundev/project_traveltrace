package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;

/**
 * 요청 바디를 "실제 나가는 모양" 그대로 고정한다. responseSchema 를 빼먹어도 모델은
 * 그럴듯한 JSON 을 돌려주므로 통합 테스트로는 회귀가 안 잡힌다 — 스키마가 와이어에
 * 실렸는지는 여기서만 증명된다(PRD §4.3 "네이티브 스키마 강제").
 */
@RunWith(RobolectricTestRunner.class)
public class VertexRequestBuilderTest {

    private static final byte[] JPEG = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

    private static JsonObject firstUserPart(JsonObject body, int index) {
        return body.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(index).getAsJsonObject();
    }

    @Test
    public void everyContentEntryCarriesARole() {
        JsonObject body = VertexRequestBuilder.build(JPEG);
        JsonArray contents = body.getAsJsonArray("contents");
        for (int i = 0; i < contents.size(); i++) {
            assertTrue("Vertex 는 role 을 필수로 요구한다 — Developer API 와 달리 생략하면 400",
                    contents.get(i).getAsJsonObject().has("role"));
        }
    }

    @Test
    public void sendsImageAsBase64InlineData() {
        JsonObject inline = firstUserPart(VertexRequestBuilder.build(JPEG), 0)
                .getAsJsonObject("inlineData");
        assertEquals("image/jpeg", inline.get("mimeType").getAsString());
        assertEquals(android.util.Base64.encodeToString(JPEG, android.util.Base64.NO_WRAP),
                inline.get("data").getAsString());
    }

    @Test
    public void forcesJsonMimeTypeAndResponseSchema() {
        JsonObject config = VertexRequestBuilder.build(JPEG).getAsJsonObject("generationConfig");
        assertEquals("application/json", config.get("responseMimeType").getAsString());
        assertTrue("responseSchema 없이 나가면 스키마 강제가 아니라 프롬프트 유도일 뿐이다",
                config.has("responseSchema"));
    }

    @Test
    public void pinsTemperatureToZero() {
        JsonObject config = VertexRequestBuilder.build(JPEG).getAsJsonObject("generationConfig");
        assertTrue("temperature 0 이 누락되면 같은 사진이 호출마다 다른 장소로 인식된다",
                config.has("temperature"));
        assertEquals(0d, config.get("temperature").getAsDouble(), 0d);
    }

    @Test
    public void schemaHasNoCoordinateFields() {
        JsonObject props = VertexRequestBuilder.build(JPEG)
                .getAsJsonObject("generationConfig")
                .getAsJsonObject("responseSchema")
                .getAsJsonObject("properties");
        assertFalse("AI 가 좌표를 만들 통로를 스키마 차원에서 막는다(PRD §4.3)", props.has("lat"));
        assertFalse(props.has("lng"));
        assertFalse(props.has("latitude"));
        assertFalse(props.has("longitude"));
        assertEquals(4, props.size());
        assertTrue(props.has("landmarkName"));
        assertTrue(props.has("city"));
        assertTrue(props.has("country"));
        assertTrue(props.has("confidence"));
    }

    @Test
    public void onlyConfidenceIsRequired() {
        JsonArray required = VertexRequestBuilder.build(JPEG)
                .getAsJsonObject("generationConfig")
                .getAsJsonObject("responseSchema")
                .getAsJsonArray("required");
        assertEquals("인식 실패는 정상 결과다 — 이름을 필수로 만들면 모델이 지어낸다",
                1, required.size());
        assertEquals("confidence", required.get(0).getAsString());
    }

    @Test
    public void includesSystemInstruction() {
        assertTrue(VertexRequestBuilder.build(JPEG).has("systemInstruction"));
    }
}
