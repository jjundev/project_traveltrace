package com.traveltrace.app.core;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 유료 외부 호출과 캐시 효율의 집계 (PRD §4.7·§4.8).
 *
 * <p>존재 이유는 <b>주장을 검증 가능하게 만드는 것</b>이다. "저장된 여행을 열면 AI 호출이
 * 0회"는 지금은 AI 코드가 아예 없어서 우연히 참이지만, S3 이 붙는 순간 우연이 아니게 된다 —
 * 그때 이 카운터가 회귀 그물이 된다. 그래서 호출 경로가 생기기 전에 미리 세운다.
 *
 * <p>배치는 io 스레드 여러 개에서 동시에 기록하므로 전부 {@link AtomicInteger} 다.
 *
 * <p>금액 집계(여행당 비용 상한)는 S4 소관이라 여기 없다 — 지금은 "몇 번 나갔나"만 센다.
 */
@Singleton
public class AnalysisCostLog {

    private static final String TAG = "AnalysisCostLog";

    private final AtomicInteger visionCalls = new AtomicInteger();
    private final AtomicInteger geocodeCalls = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger cacheMisses = new AtomicInteger();

    @Inject
    public AnalysisCostLog() {
    }

    /** 호출이 예외로 끝나도 센다 — 실패한 호출도 토큰을 쓴다. */
    public void recordVisionCall() {
        visionCalls.incrementAndGet();
    }

    public void recordGeocodeCall() {
        geocodeCalls.incrementAndGet();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public int visionCalls() {
        return visionCalls.get();
    }

    public int geocodeCalls() {
        return geocodeCalls.get();
    }

    public int cacheHits() {
        return cacheHits.get();
    }

    public int cacheMisses() {
        return cacheMisses.get();
    }

    /** 새 배치를 시작할 때 부른다. */
    public void reset() {
        visionCalls.set(0);
        geocodeCalls.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /** 완료 요약을 logcat 에 남긴다 — "비용 로그 확인" 수용 기준의 육안 확인용. */
    public void logSummary(String label) {
        Log.i(TAG, label + " — vision=" + visionCalls() + " geocode=" + geocodeCalls()
                + " cacheHit=" + cacheHits() + " cacheMiss=" + cacheMisses());
    }
}
