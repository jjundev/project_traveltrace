package com.traveltrace.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.data.GeocoderStub;
import com.traveltrace.app.data.VisionProviderStub;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        VisionProviderStub vision = new VisionProviderStub(log);

        try {
            vision.recognize(new byte[]{1, 2, 3});
            fail("스텁은 아직 미구현 예외를 던져야 한다");
        } catch (UnsupportedOperationException expected) {
            // 던지든 말든 "나갔다"는 사실이 비용이다 — 성공 응답만 세면 실패한 호출의
            // 토큰 비용이 통계에서 사라진다.
        }

        assertEquals("호출은 예외로 끝나도 세야 한다", 1, log.visionCalls());
    }

    @Test
    public void geocodeCallsAreCounted() {
        AnalysisCostLog log = new AnalysisCostLog();
        GeocoderStub geocoder = new GeocoderStub(log);

        geocoder.geocode(new GeocodeQuery.Poi("에펠탑"));

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
}
