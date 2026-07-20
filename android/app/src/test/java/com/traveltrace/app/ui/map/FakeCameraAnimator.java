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
    private final java.util.List<Listener> listeners = new java.util.ArrayList<>();

    @Override
    public void animateTo(double lat, double lng, float zoom, long durationMs, Listener l) {
        lastLat = lat;
        lastLng = lng;
        lastZoom = zoom;
        lastDurationMs = durationMs;
        animateCount++;
        listener = l;
        listeners.add(l);
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

    /**
     * 가장 최근이 아닌, 그 <em>이전</em> 비행의 리스너에 도착을 발화한다 — 새 비행이 이미
     * 시작된 뒤에야 뒤늦게 도착하는 스테일 콜백을 흉내 낸다 (실제 {@code GoogleMap} 이 다음
     * 프레임에 {@code onCancel}/{@code onArrive} 를 비동기로 주는 그 타이밍).
     */
    public void arrivePrevious() {
        previousListener().onArrive();
    }

    /** {@link #arrivePrevious()} 의 취소 버전. */
    public void cancelPrevious() {
        previousListener().onCancel();
    }

    private Listener previousListener() {
        if (listeners.size() < 2) {
            throw new IllegalStateException("이전 비행이 없다 — animateTo() 가 두 번 이상 불려야 한다");
        }
        return listeners.get(listeners.size() - 2);
    }
}
