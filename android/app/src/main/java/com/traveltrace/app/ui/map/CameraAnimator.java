package com.traveltrace.app.ui.map;

/**
 * 지도 카메라를 움직이는 최소 계약. Maps SDK 타입(CameraUpdate·LatLng·GoogleMap)을
 * 일부러 노출하지 않는다 — 그 타입들은 단위 테스트 환경에서 초기화되지 않아
 * 재생 로직 전체를 테스트 불가능하게 만든다. 여기 남는 건 위경도·줌·시간뿐이다.
 */
public interface CameraAnimator {

    interface Listener {
        /** 목적지에 정상 도착. */
        void onArrive();

        /** 도착 전에 애니메이션이 끊겼다 (다른 애니메이션·정지 요청·사용자 제스처). */
        void onCancel();
    }

    void animateTo(double lat, double lng, float zoom, long durationMs, Listener listener);

    /** 애니메이션 없이 즉시 이동. */
    void moveTo(double lat, double lng, float zoom);

    /** 진행 중 애니메이션을 현재 위치에서 멈춘다. 콜백은 onCancel 로 온다. */
    void stop();
}
