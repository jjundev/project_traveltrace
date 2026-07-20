package com.traveltrace.app.data.vision;

import com.google.gson.JsonObject;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Response;

/**
 * {@link VisionProvider} 의 Vertex AI 구현 (PRD §4.3, S3 단일 프로바이더).
 *
 * <p>블로킹이다 — 인터페이스가 그렇게 정의돼 있고, 호출자는 이미 백그라운드
 * 스레드({@code AppExecutors.io()}) 위에 있다.
 *
 * <p><b>두 종류의 실패를 구분한다.</b> HTTP·네트워크 실패는 {@link IOException} 으로
 * <em>던지고</em>, 모델이 "모르겠다"고 답한 것은 confidence 0 인 정상 결과로 <em>돌려준다</em>.
 * 이 구분이 S4 의 재시도·백오프가 붙을 자리를 만든다 — 실내 사진을 재시도해봐야
 * 돈만 쓴다.
 *
 * <p>모델 ID 는 {@link BuildConfig#GEMINI_VISION_MODEL}(빌드 시점 Vertex 에서 확정)에서
 * 읽는다. 여기에 문자열 리터럴로 쓰면 {@code modelIdGuard} 가 빌드를 깬다 — 의도된 방어다.
 */
@Singleton
public class VertexGeminiProvider implements VisionProvider {

    private final VertexApi api;

    @Inject
    public VertexGeminiProvider(VertexApi api) {
        this.api = api;
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) throws Exception {
        JsonObject body = VertexRequestBuilder.build(imageJpeg);
        Response<JsonObject> response = api.generateContent(
                BuildConfig.GEMINI_VISION_MODEL, BuildConfig.VERTEX_API_KEY, body).execute();

        if (!response.isSuccessful()) {
            // 404 는 십중팔구 죽은/미노출 모델 ID 다 — ./gradlew resolveVisionModels 를 다시 돌려야 한다.
            throw new IOException("Vertex generateContent HTTP " + response.code());
        }
        JsonObject payload = response.body();
        if (payload == null) {
            throw new IOException("Vertex generateContent returned an empty body");
        }
        return VertexResponseParser.parse(payload);
    }
}
