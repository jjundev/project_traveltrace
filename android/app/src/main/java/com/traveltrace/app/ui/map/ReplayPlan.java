package com.traveltrace.app.ui.map;

/**
 * 리플레이 타이밍·줌 계산. Android·Maps 타입에 전혀 의존하지 않는 순수 함수라
 * 숫자 감각을 단위 테스트로 못 박을 수 있다.
 *
 * <p>프로토타입(prototype/TravelTrace.html)의 {@code viewDwell()}/{@code startFlight()} 가
 * 쓰던 값을 옮겨 왔다. 다만 프로토타입의 거리는 가짜 지도의 % 좌표였으므로, 여기선 실제
 * 미터로 바꾸고 "먼 이동" 기준을 {@link #FAR_METERS} 로 다시 잡았다. tilt/rotate 합성은
 * CSS 3D 전용이라 이식하지 않는다 — 2D 팬/줌만 쓴다(plan/13).
 */
public final class ReplayPlan {

    /** 도착 후 다음 스톱으로 떠나기까지 머무는 시간 (plan/ISSUES.md S2 확정값). */
    public static final long DWELL_RELAXED_MS = 2800L;
    public static final long DWELL_NORMAL_MS = 1800L;
    public static final long DWELL_FAST_MS = 1100L;

    /** 거리 0 일 때의 기준 비행 시간. 실제 시간은 거리에 따라 이 값을 늘린다. */
    private static final long FLIGHT_BASE_RELAXED_MS = 2600L;
    private static final long FLIGHT_BASE_NORMAL_MS = 1900L;
    private static final long FLIGHT_BASE_FAST_MS = 1300L;

    /** 아무리 짧아도 이보다 빠르면 눈이 이동을 못 따라간다. */
    public static final long FLIGHT_MIN_MS = 1200L;

    /** 이 거리 이상은 전부 "먼 이동"으로 같게 취급한다 — 도시 간 이동에서 시간이 폭주하지 않게. */
    private static final double FAR_METERS = 5_000d;

    /** 스톱에 멈춰 있을 때의 줌. 상영 모드는 사진에 더 붙는다(프로토타입 zBase 2.0→2.4 의도). */
    public static final float REST_ZOOM = 14f;
    public static final float REST_ZOOM_CINEMA = 15f;

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private ReplayPlan() {}

    public static long dwellMs(MapUiState.Speed speed) {
        switch (speed) {
            case RELAXED: return DWELL_RELAXED_MS;
            case FAST: return DWELL_FAST_MS;
            default: return DWELL_NORMAL_MS;
        }
    }

    private static long flightBaseMs(MapUiState.Speed speed) {
        switch (speed) {
            case RELAXED: return FLIGHT_BASE_RELAXED_MS;
            case FAST: return FLIGHT_BASE_FAST_MS;
            default: return FLIGHT_BASE_NORMAL_MS;
        }
    }

    /** 기준시간 × (0.7 ~ 1.2), 거리 비례. 바닥은 {@link #FLIGHT_MIN_MS}. */
    public static long flightMs(MapUiState.Speed speed, double meters) {
        double k = Math.min(Math.max(meters, 0d) / FAR_METERS, 1d);
        long raw = Math.round(flightBaseMs(speed) * (0.7d + k * 0.5d));
        return Math.max(FLIGHT_MIN_MS, raw);
    }

    public static float restZoom(boolean cinema) {
        return cinema ? REST_ZOOM_CINEMA : REST_ZOOM;
    }

    /**
     * 두 좌표 사이 대권 거리(m). android-maps-utils 를 끌어오지 않으려고 직접 구현한다
     * (프로토타입 "자체 구현" 결정과 같은 이유).
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1d, Math.sqrt(a)));
    }
}
