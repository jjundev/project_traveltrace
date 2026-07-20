package com.traveltrace.app.di;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.data.vision.VertexApi;

import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 이 앱 최초의 Retrofit 배선. 호스트가 셋(Vertex·Places·Geocoding)이라 Retrofit 인스턴스도
 * 셋이지만, {@link OkHttpClient} 와 {@link Gson} 은 하나를 공유한다 — 커넥션 풀·스레드풀을
 * 세 벌 만들 이유가 없다.
 *
 * <p><b>타임아웃은 기본값을 쓰지 않는다.</b> OkHttp 기본 read 타임아웃(10s)은 큰 이미지의
 * Vertex 추론엔 짧고, 무한대는 배치를 영원히 멈춘다. PRD §4.8 의 콜당 30s 를 여기 박아
 * S4 가 정책을 세우기 전까지의 안전장치로 삼는다.
 *
 * <p>로깅은 디버그 빌드에서 HEADERS 까지만이다. BODY 로 올리면 base64 이미지 수 MB 가
 * logcat 에 쏟아지고, 무엇보다 요청 헤더에 실린 API 키가 그대로 찍힌다.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class NetworkModule {

    private static final String VERTEX_BASE_URL = "https://aiplatform.googleapis.com/";

    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long READ_TIMEOUT_SECONDS = 30L;

    private NetworkModule() {}

    @Provides
    @Singleton
    public static Gson provideGson() {
        return new GsonBuilder().create();
    }

    @Provides
    @Singleton
    public static OkHttpClient provideOkHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.HEADERS
                : HttpLoggingInterceptor.Level.NONE);
        logging.redactHeader("x-goog-api-key");
        logging.redactHeader("X-Goog-Api-Key");
        return new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    @Provides
    @Singleton
    public static VertexApi provideVertexApi(OkHttpClient client, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(VERTEX_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(VertexApi.class);
    }
}
