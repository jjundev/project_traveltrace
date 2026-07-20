package com.traveltrace.app.data.vision;

import com.google.gson.JsonObject;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.ResponseBody;
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
    private final AnalysisCostLog costLog;

    @Inject
    public VertexGeminiProvider(VertexApi api, AnalysisCostLog costLog) {
        this.api = api;
        this.costLog = costLog;
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) throws Exception {
        // 유료 호출은 이 경계에서 센다(S8) — HTTP 실패로 끝나도 토큰은 쓰이므로 호출 전에
        // 기록한다. "저장 여행을 열면 vision=0" 수용 기준은 이 지점이 구현 안이 아니라
        // provider 경계에 있어야 캐시 hit 로 provider 가 안 불릴 때 0 이 유지된다.
        costLog.recordVisionCall();
        JsonObject body = VertexRequestBuilder.build(imageJpeg);
        Response<JsonObject> response = api.generateContent(
                BuildConfig.GEMINI_VISION_MODEL, BuildConfig.VERTEX_API_KEY, body).execute();

        if (!response.isSuccessful()) {
            // 404 는 십중팔구 죽은/미노출 모델 ID 다 — ./gradlew resolveVisionModels 를 다시 돌려야 한다.
            // errorBody() 는 열려 있는 커넥션을 물고 있다 — 여기서 소비/close 하지 않으면
            // OkHttp 커넥션 풀로 돌아가지 않고 비결정적으로만 회수된다(S4 재시도가 이 경로를
            // 반복 호출하면 누수가 누적된다).
            String detail = readAndCloseErrorBody(response);
            throw new IOException("Vertex generateContent HTTP " + response.code()
                    + (detail == null ? "" : ": " + detail));
        }
        JsonObject payload = response.body();
        if (payload == null) {
            throw new IOException("Vertex generateContent returned an empty body");
        }
        return VertexResponseParser.parse(payload);
    }

    /**
     * 에러 바디를 읽어 진단용 문자열로 돌려주고, 어떤 경로로든 커넥션을 닫는다.
     *
     * <p>{@link ResponseBody#string()} 은 읽는 과정에서 스스로 body 를 소비/close 하므로
     * 성공 경로에서 별도 close 는 필요 없다. 다만 body 가 null 이거나 읽기 자체가
     * 실패하는 경우까지 대비해 방어적으로 닫는다 — 이 메서드는 절대 예외를 던지지
     * 않는다: 진짜 HTTP 상태 예외를 가려서는 안 되기 때문이다.
     */
    private static String readAndCloseErrorBody(Response<?> response) {
        ResponseBody errorBody = response.errorBody();
        if (errorBody == null) {
            return null;
        }
        try {
            String text = errorBody.string();
            if (text == null || text.isEmpty()) {
                return null;
            }
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (IOException e) {
            return null;
        } finally {
            errorBody.close();
        }
    }
}
