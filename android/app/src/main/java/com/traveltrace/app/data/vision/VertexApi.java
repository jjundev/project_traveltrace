package com.traveltrace.app.data.vision;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Vertex AI Express Mode. 프로젝트 ID·리전·서비스 계정이 경로에 없는 것이 Express 의
 * 정의다 — API 키 헤더 하나로 인증한다. base URL 은 {@code https://aiplatform.googleapis.com/}
 * (NetworkModule).
 *
 * <p>경로의 {@code :generateContent} 는 리터럴이다. Retrofit 은 {@code {model}} 만
 * 치환하고 콜론 뒤는 그대로 둔다.
 */
public interface VertexApi {

    @POST("v1/publishers/google/models/{model}:generateContent")
    Call<JsonObject> generateContent(@Path("model") String model,
                                     @Header("x-goog-api-key") String apiKey,
                                     @Body JsonObject body);
}
