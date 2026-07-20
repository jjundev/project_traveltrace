package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@RunWith(RobolectricTestRunner.class)
public class VertexGeminiProviderTest {

    private static final byte[] JPEG = "jpeg".getBytes(StandardCharsets.UTF_8);

    @Test
    public void parsesASuccessfulResponse() throws Exception {
        RecordingApi api = RecordingApi.succeeding(
                "{\"landmarkName\":\"에펠탑\",\"city\":\"파리\",\"country\":\"프랑스\",\"confidence\":0.9}");

        RecognitionResult r = new VertexGeminiProvider(api).recognize(JPEG);

        assertEquals("에펠탑", r.landmarkName);
        assertEquals(0.9d, r.confidence, 1e-9);
    }

    @Test
    public void sendsTheResolvedModelIdAndKey() throws Exception {
        RecordingApi api = RecordingApi.succeeding("{\"confidence\":0}");

        new VertexGeminiProvider(api).recognize(JPEG);

        assertNotNull("모델 ID 가 비면 Vertex 가 404 를 낸다", api.model);
        assertTrue("모델 ID 는 BuildConfig 에서 온다 — 하드코딩하면 modelIdGuard 가 빌드를 깬다",
                api.model.length() > 0);
        assertNotNull(api.apiKey);
        assertTrue("요청 바디는 VertexRequestBuilder 산출물이어야 한다",
                api.body.has("generationConfig"));
    }

    @Test
    public void httpFailureThrowsSoTheCallerCanCountIt() {
        RecordingApi api = RecordingApi.failingWith(429);
        try {
            new VertexGeminiProvider(api).recognize(JPEG);
            fail("HTTP 실패는 인식 실패와 구분돼야 한다 — S4 재시도/백오프가 이 예외에 붙는다");
        } catch (Exception expected) {
            assertTrue(expected instanceof IOException);
            assertTrue(expected.getMessage().contains("429"));
        }
    }

    @Test
    public void nullBodyOnSuccessThrows() {
        RecordingApi api = RecordingApi.succeedingWithNullBody();
        try {
            new VertexGeminiProvider(api).recognize(JPEG);
            fail("바디가 비면 파싱할 게 없다 — 빈 결과를 정상으로 취급하면 안 된다");
        } catch (Exception expected) {
            assertTrue(expected instanceof IOException);
        }
    }

    /** 테스트 안에서만 쓰는 손수 만든 페이크. 이 저장소는 모킹 라이브러리를 쓰지 않는다. */
    private static final class RecordingApi implements VertexApi {
        String model;
        String apiKey;
        JsonObject body;
        private final JsonObject success;
        private final int errorCode;

        private RecordingApi(JsonObject success, int errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }

        static RecordingApi succeeding(String modelJson) {
            JsonObject part = new JsonObject();
            part.addProperty("text", modelJson);
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject content = new JsonObject();
            content.add("parts", parts);
            JsonObject candidate = new JsonObject();
            candidate.add("content", content);
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            JsonObject root = new JsonObject();
            root.add("candidates", candidates);
            return new RecordingApi(root, 0);
        }

        static RecordingApi failingWith(int code) {
            return new RecordingApi(null, code);
        }

        /** 2xx 이지만 바디가 없는 응답 — {@code errorCode == 0} 은 성공 경로를 태우고, 성공값이
         * {@code null} 이라 {@code Response.success(null)} 이 만들어진다. */
        static RecordingApi succeedingWithNullBody() {
            return new RecordingApi(null, 0);
        }

        @Override
        public Call<JsonObject> generateContent(String model, String apiKey, JsonObject body) {
            this.model = model;
            this.apiKey = apiKey;
            this.body = body;
            return new StubCall(errorCode == 0
                    ? Response.success(success)
                    : Response.error(errorCode, ResponseBody.create(
                            MediaType.parse("application/json"), "{\"error\":\"nope\"}")));
        }
    }

    /** execute() 만 의미 있는 최소 Call. 나머지는 이 코드 경로에서 호출되지 않는다. */
    private static final class StubCall implements Call<JsonObject> {
        private final Response<JsonObject> response;

        StubCall(Response<JsonObject> response) {
            this.response = response;
        }

        @Override public Response<JsonObject> execute() { return response; }
        @Override public void enqueue(Callback<JsonObject> callback) { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return true; }
        @Override public void cancel() {}
        @Override public boolean isCanceled() { return false; }
        @Override public Call<JsonObject> clone() { return new StubCall(response); }
        @Override public okhttp3.Request request() { throw new UnsupportedOperationException(); }
        @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
    }
}
