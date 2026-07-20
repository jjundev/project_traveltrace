package com.traveltrace.app.ui.map;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

/**
 * {@link CameraAnimator} → 표준 Maps SDK. 로직이 하나도 없는 어댑터로 유지한다 —
 * 여기 조건문이 생기면 그건 {@link ReplayCamera}·{@code ReplayEngine} 로 가야 할 로직이다.
 */
public final class GoogleMapCameraAnimator implements CameraAnimator {

    private final GoogleMap map;

    public GoogleMapCameraAnimator(GoogleMap map) {
        this.map = map;
    }

    @Override
    public void animateTo(double lat, double lng, float zoom, long durationMs, Listener listener) {
        int duration = (int) Math.max(1L, Math.min(durationMs, Integer.MAX_VALUE));
        map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom),
                duration,
                new GoogleMap.CancelableCallback() {
                    @Override
                    public void onFinish() {
                        listener.onArrive();
                    }

                    @Override
                    public void onCancel() {
                        listener.onCancel();
                    }
                });
    }

    @Override
    public void moveTo(double lat, double lng, float zoom) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom));
    }

    @Override
    public void stop() {
        map.stopAnimation();
    }
}
