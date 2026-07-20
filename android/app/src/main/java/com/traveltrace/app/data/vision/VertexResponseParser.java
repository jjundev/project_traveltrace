package com.traveltrace.app.data.vision;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.RecognitionResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vertex 응답 → {@link RecognitionResult}.
 *
 * <p><b>절대 던지지 않는다.</b> 스키마 위반·깨진 JSON·안전필터 차단·빈 candidates 는
 * 전부 {@link #UNRECOGNIZED}(confidence 0) 로 수렴한다 — plan/08 DoD 가 요구하는
 * "스키마 위반은 파싱 에러가 아니라 인식 실패" 계약이다. 실내·음식 사진의 인식 실패는
 * 정상 경로이므로(PRD §8), 여기서 예외를 던지면 사진 1장이 배치를 중단시킨다.
 */
public final class VertexResponseParser {

    /** 인식 실패. 이름 없음 + confidence 0 → 상위에서 UNKNOWN 으로 분류된다. */
    public static final RecognitionResult UNRECOGNIZED =
            new RecognitionResult(null, null, null, 0d);

    private static final Pattern CODE_FENCE =
            Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$");

    private VertexResponseParser() {}

    public static RecognitionResult parse(JsonObject response) {
        String text = firstText(response);
        if (text == null) {
            return UNRECOGNIZED;
        }
        JsonObject payload = parseObject(stripCodeFences(text));
        if (payload == null) {
            return UNRECOGNIZED;
        }
        // confidence 부재 = 스키마 위반. 이름만 믿고 임의의 신뢰도를 만들어내면
        // 저신뢰 강등(NAME_ONLY)이 통째로 무력해진다.
        if (!payload.has("confidence") || !payload.get("confidence").isJsonPrimitive()) {
            return UNRECOGNIZED;
        }
        double confidence;
        try {
            confidence = payload.get("confidence").getAsDouble();
        } catch (NumberFormatException notANumber) {
            return UNRECOGNIZED;
        }
        return new RecognitionResult(
                optionalString(payload, "landmarkName"),
                optionalString(payload, "city"),
                optionalString(payload, "country"),
                confidence);
    }

    @Nullable
    private static String firstText(@Nullable JsonObject response) {
        if (response == null || !response.has("candidates")) {
            return null;   // 안전필터 차단 시 candidates 자체가 없다.
        }
        JsonElement candidatesElement = response.get("candidates");
        if (!candidatesElement.isJsonArray()) {
            return null;
        }
        JsonArray candidates = candidatesElement.getAsJsonArray();
        if (candidates.size() == 0) {
            return null;
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) {
            return null;
        }
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts.size() == 0) {
            return null;
        }
        JsonObject part = parts.get(0).getAsJsonObject();
        return part.has("text") ? part.get("text").getAsString() : null;
    }

    /** responseSchema 를 써도 모델이 가끔 펜스를 붙인다 — 방어적으로 벗긴다. */
    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        Matcher fenced = CODE_FENCE.matcher(trimmed);
        return fenced.matches() ? fenced.group(1).trim() : trimmed;
    }

    @Nullable
    private static JsonObject parseObject(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Nullable
    private static String optionalString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String value = object.get(key).getAsString();
        return value.trim().isEmpty() ? null : value;
    }
}
