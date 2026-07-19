package com.traveltrace.app.ui.map;

/**
 * 지도 없이 카메라 동작을 검증하기 위한 테스트 더블. 도착/취소를 테스트가 직접 발화한다
 * — 실제 {@code GoogleMap} 은 콜백을 다음 프레임에 비동기로 주므로 그 타이밍도 흉내 낼 수 있다.
 */
public class FakeCameraAnimator implements CameraAnimator {

    public double lastLat;
    public double lastLng;
    public float lastZoom;
    public long lastDurationMs;
    public int animateCount;
    public int moveCount;
    public int stopCount;

    private Listener listener;

    @Override
    public void animateTo(double lat, double lng, float zoom, long durationMs, Listener l) {
        lastLat = lat;
        lastLng = lng;
        lastZoom = zoom;
        lastDurationMs = durationMs;
        animateCount++;
        listener = l;
    }

    @Override
    public void moveTo(double lat, double lng, float zoom) {
        lastLat = lat;
        lastLng = lng;
        lastZoom = zoom;
        moveCount++;
    }

    @Override
    public void stop() {
        stopCount++;
    }

    /** 진행 중인 애니메이션이 목적지에 닿았다고 알린다. */
    public void arrive() {
        if (listener != null) listener.onArrive();
    }

    /** 애니메이션이 중단됐다고 알린다 (사용자 제스처·stopAnimation 양쪽 모두 이 경로다). */
    public void cancel() {
        if (listener != null) listener.onCancel();
    }
}
