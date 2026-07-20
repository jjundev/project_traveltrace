package com.traveltrace.app.ui.map;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.function.LongSupplier;

/**
 * 비행 1건의 생명주기. 프로토타입 {@code freezeFlight}/{@code resumeFlight} 의 네이티브 등가물이다
 * — rAF 로 프레임을 직접 굴리는 대신, 진행 중 애니메이션을 멈추고 <em>남은 시간만큼</em>
 * 현재 위치에서 목적지로 다시 애니메이션한다.
 *
 * <p><b>세대(generation) 가드가 핵심이다.</b> 우리가 {@code stop()} 을 부르면 실제
 * {@code GoogleMap} 은 {@code onCancel} 을 <em>다음 프레임에 비동기로</em> 준다. "지금 내가 멈추는
 * 중"이라는 순간 플래그로는 그 늦은 콜백을 걸러낼 수 없어서, 우리가 의도적으로 끊을 때마다
 * 세대를 올리고 옛 세대의 콜백은 전부 버린다. 이게 없으면 일시정지가 "사용자가 방해함"으로
 * 오인되어 재생이 죽는다.
 */
public class ReplayCamera {

    /** 비행 결과. */
    public interface Arrival {
        void onArrive();

        /** 우리가 아닌 무언가가 비행을 끊었다 (사용자 제스처 등). */
        void onInterrupted();
    }

    private final CameraAnimator animator;
    private final LongSupplier clock;

    @Nullable private Arrival arrival;
    private double targetLat;
    private double targetLng;
    private float targetZoom;
    private long totalMs;
    private long elapsedMs;
    private long startedAtMs;
    private boolean inFlight;
    private boolean frozen;
    private int generation;

    public ReplayCamera(CameraAnimator animator) {
        this(animator, SystemClock::uptimeMillis);
    }

    ReplayCamera(CameraAnimator animator, LongSupplier clock) {
        this.animator = animator;
        this.clock = clock;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** 남은 비행 시간. 최소 1ms — 0 을 주면 SDK 가 이동을 건너뛴다. */
    public long remainingMs() {
        return Math.max(1L, totalMs - elapsedMs);
    }

    public void flyTo(double lat, double lng, float zoom, long durationMs, Arrival cb) {
        cancel();
        arrival = cb;
        targetLat = lat;
        targetLng = lng;
        targetZoom = zoom;
        totalMs = Math.max(1L, durationMs);
        elapsedMs = 0L;
        startedAtMs = clock.getAsLong();
        inFlight = true;
        frozen = false;
        animate(totalMs);
    }

    /** 공중 정지. 얼릴 비행이 없으면 false. */
    public boolean freeze() {
        if (!inFlight || frozen) return false;
        elapsedMs += clock.getAsLong() - startedAtMs;
        generation++; // 이 뒤에 오는 콜백은 전부 우리가 만든 중단이다 — 버린다.
        animator.stop();
        frozen = true;
        return true;
    }

    /** 동결 지점부터 잔여 구간을 이어간다. 얼려 둔 비행이 없으면 false. */
    public boolean resume() {
        if (!inFlight || !frozen) return false;
        frozen = false;
        startedAtMs = clock.getAsLong();
        animate(remainingMs());
        return true;
    }

    /** 비행을 버린다 — 콜백은 도착으로도 중단으로도 보고되지 않는다. */
    public void cancel() {
        generation++;
        if (inFlight) animator.stop();
        inFlight = false;
        frozen = false;
        arrival = null;
        elapsedMs = 0L;
        totalMs = 0L;
    }

    /** 애니메이션 없이 즉시 이동 (진행 중 비행은 버린다). */
    public void moveTo(double lat, double lng, float zoom) {
        cancel();
        animator.moveTo(lat, lng, zoom);
    }

    private void animate(long durationMs) {
        final int gen = ++generation;
        animator.animateTo(targetLat, targetLng, targetZoom, durationMs,
                new CameraAnimator.Listener() {
                    @Override
                    public void onArrive() {
                        if (gen != generation || !inFlight) return;
                        Arrival cb = settle();
                        if (cb != null) cb.onArrive();
                    }

                    @Override
                    public void onCancel() {
                        if (gen != generation || !inFlight) return;
                        Arrival cb = settle();
                        if (cb != null) cb.onInterrupted();
                    }
                });
    }

    @Nullable
    private Arrival settle() {
        Arrival cb = arrival;
        inFlight = false;
        frozen = false;
        arrival = null;
        return cb;
    }
}
