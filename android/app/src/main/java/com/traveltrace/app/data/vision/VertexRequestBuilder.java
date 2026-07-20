package com.traveltrace.app.data.vision;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Vertex AI {@code :generateContent} 요청 바디 조립 (PRD §4.3).
 *
 * <p><b>스키마는 프롬프트가 아니라 {@code responseSchema} 로 강제한다.</b> 프롬프트로
 * "JSON 으로 답해"라고만 하면 모델이 코드펜스·설명문을 붙이거나 필드명을 바꾼다.
 *
 * <p><b>Vertex 는 모든 content 항목에 {@code role} 을 요구한다</b> — Developer API 는
 * 생략을 허용했지만 Vertex 는 400 을 낸다. 두 API 의 실질적 차이가 이것과 호스트뿐이라
 * 빠뜨리기 쉽다.
 *
 * <p>좌표 필드는 스키마에 <em>없다</em>. AI 가 lat/lng 를 만들지 못하게 막는 것이
 * {@link com.traveltrace.app.core.model.RecognitionResult} 에 좌표가 없는 이유와 같고,
 * 여기가 그 계약의 바깥쪽 절반이다(§4.2-2).
 */
public final class VertexRequestBuilder {

    private static final String MIME_JPEG = "image/jpeg";

    /**
     * 영어 프롬프트다 — 한국어 리터럴 금지 규약은 <em>사용자 대면</em> 문자열 대상이고,
     * 이건 모델에게 가는 문자열이라 화면에 뜨지 않는다. 한국어 지명을 돌려받기 위해
     * 출력 언어만 명시적으로 지정한다.
     */
    private static final String SYSTEM_INSTRUCTION =
            "You identify where a travel photo was taken. Return ONLY the NAME of the place — "
                    + "never coordinates. If a specific landmark or point of interest is clearly "
                    + "visible, put it in landmarkName. Fill city and country whenever you can infer "
                    + "them. If the photo shows a generic interior, food, a close-up, or anything "
                    + "without a locatable cue, leave the names null and set confidence to 0 — "
                    + "failing to recognise is a correct answer and is far better than guessing. "
                    + "confidence is your calibrated probability (0.0-1.0) that the named place is "
                    + "correct. Write place names in Korean.";

    private VertexRequestBuilder() {}

    public static JsonObject build(byte[] jpeg) {
        JsonObject inlineData = new JsonObject();
        inlineData.addProperty("mimeType", MIME_JPEG);
        inlineData.addProperty("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));

        JsonObject imagePart = new JsonObject();
        imagePart.add("inlineData", inlineData);

        JsonArray parts = new JsonArray();
        parts.add(imagePart);

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");   // Vertex 필수
        userContent.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());
        // 같은 사진이 호출마다 다른 장소로 인식되면 캐시(S4)와 사용자 신뢰가 함께 무너진다.
        generationConfig.addProperty("temperature", 0);

        JsonObject systemParts = new JsonObject();
        systemParts.addProperty("text", SYSTEM_INSTRUCTION);
        JsonArray systemPartArray = new JsonArray();
        systemPartArray.add(systemParts);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemPartArray);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        body.add("systemInstruction", systemInstruction);
        return body;
    }

    /** Gemini 의 OpenAPI 서브셋 — 타입명은 대문자다. */
    static JsonObject responseSchema() {
        JsonObject properties = new JsonObject();
        properties.add("landmarkName", nullableString());
        properties.add("city", nullableString());
        properties.add("country", nullableString());

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "NUMBER");
        properties.add("confidence", confidence);

        // confidence 만 required. 이름을 required 로 만들면 모델이 빈 문자열을 지어내
        // "인식 실패"라는 정상 결과를 표현할 방법이 사라진다.
        JsonArray required = new JsonArray();
        required.add("confidence");

        JsonArray ordering = new JsonArray();
        ordering.add("landmarkName");
        ordering.add("city");
        ordering.add("country");
        ordering.add("confidence");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.add("propertyOrdering", ordering);
        return schema;
    }

    private static JsonObject nullableString() {
        JsonObject field = new JsonObject();
        field.addProperty("type", "STRING");
        field.addProperty("nullable", true);
        return field;
    }
}
