package com.traveltrace.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.data.geocode.RoutingGeocoder;
import com.traveltrace.app.data.vision.VertexGeminiProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 비용 카운터의 계약과, 그 카운터가 <b>provider 경계</b>에서 실제로 증가하는지 검증한다.
 *
 * <p>S8 은 원래 스텁(VisionProviderStub/GeocoderStub)에서 카운트했지만, S3 이 스텁을
 * 실구현({@link VertexGeminiProvider}/{@link RoutingGeocoder})으로 교체하면서 카운트 지점도
 * 그 경계로 옮겨왔다 — "저장 여행을 열면 vision=0"이 실구현에서도 계속 참이려면 세는 곳이
 * 구현 안이 아니라 경계에 있어야 하기 때문이다. 여기서 그 경계 계약을 실 provider 로 검증한다.
 */
@RunWith(RobolectricTestRunner.class)
public class AnalysisCostLogTest {

    @Test
    public void countersStartAtZero() {
        AnalysisCostLog log = new AnalysisCostLog();

        assertEquals(0, log.visionCalls());
        assertEquals(0, log.geocodeCalls());
        assertEquals(0, log.cacheHits());
        assertEquals(0, log.cacheMisses());
    }

    @Test
    public void everyVisionCallIsCountedEvenWhenItThrows() {
        AnalysisCostLog log = new AnalysisCostLog();
        // 실 provider 는 HTTP 를 때리기 전에 센다 — 그래서 호출이 예외로 끝나도 카운트가 남는다.
        VertexGeminiProvider vision =
                new VertexGeminiProvider((model, apiKey, body) -> throwingCall(), log);

        try {
            vision.recognize(new byte[]{1, 2, 3});
            fail("HTTP 실패는 예외로 전파돼야 한다");
        } catch (Exception expected) {
            // 던지든 말든 "나갔다"는 사실이 비용이다 — 성공 응답만 세면 실패한 호출의
            // 토큰 비용이 통계에서 사라진다.
        }

        assertEquals("호출은 예외로 끝나도 세야 한다", 1, log.visionCalls());
    }

    @Test
    public void geocodeCallsAreCounted() {
        AnalysisCostLog log = new AnalysisCostLog();
        RoutingGeocoder geocoder = new RoutingGeocoder(
                (apiKey, fieldMask, body) -> throwingCall(),   // PlacesTextSearchApi
                (address, key) -> throwingCall(),              // GeocodingApi
                log);

        try {
            geocoder.geocode(new GeocodeQuery.Poi("에펠탑"));
            fail("HTTP 실패는 예외로 전파돼야 한다");
        } catch (Exception expected) {
            // 실패한 콜도 나간 콜이다.
        }

        assertEquals(1, log.geocodeCalls());
    }

    @Test
    public void resetClearsEveryCounter() {
        AnalysisCostLog log = new AnalysisCostLog();
        log.recordCacheHit();
        log.recordCacheMiss();
        log.recordGeocodeCall();

        log.reset();

        assertEquals(0, log.geocodeCalls());
        assertEquals(0, log.cacheHits());
        assertEquals(0, log.cacheMisses());
    }

    @Test
    public void countingIsSafeFromTheIoPool() throws Exception {
        // 배치는 io 스레드 여러 개에서 동시에 기록한다 — int++ 였다면 여기서 깨진다.
        AnalysisCostLog log = new AnalysisCostLog();
        AppExecutors executors = new AppExecutors();
        int perThread = 500;
        int threads = 4;
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executors.io().execute(() -> {
                for (int i = 0; i < perThread; i++) log.recordCacheHit();
                done.countDown();
            });
        }

        if (!done.await(5, TimeUnit.SECONDS)) fail("5초 안에 끝나야 한다");
        assertEquals(threads * perThread, log.cacheHits());
        executors.shutdown();
    }

    /** execute() 가 IOException 을 던지는 최소 Retrofit Call — 나머지 메서드는 이 경로에서 안 쓰인다. */
    private static Call<JsonObject> throwingCall() {
        return new Call<JsonObject>() {
            @Override public Response<JsonObject> execute() throws IOException {
                throw new IOException("boom");
            }
            @Override public void enqueue(Callback<JsonObject> callback) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isExecuted() { return false; }
            @Override public void cancel() {}
            @Override public boolean isCanceled() { return false; }
            @Override public Call<JsonObject> clone() { return throwingCall(); }
            @Override public Request request() { throw new UnsupportedOperationException(); }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }
}
