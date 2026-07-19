package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 리플레이 타이밍은 제품 감각을 좌우하는 숫자라 전부 여기서 못 박는다.
 * dwell 값은 plan/ISSUES.md S2 가 프로토타입 결정으로 확정한 값이다.
 */
public class ReplayPlanTest {

    @Test
    public void dwellFollowsTheSpeedPreset() {
        assertEquals(2800L, ReplayPlan.dwellMs(MapUiState.Speed.RELAXED));
        assertEquals(1800L, ReplayPlan.dwellMs(MapUiState.Speed.NORMAL));
        assertEquals(1100L, ReplayPlan.dwellMs(MapUiState.Speed.FAST));
    }

    @Test
    public void flightGrowsWithDistanceUpToTheFarThreshold() {
        long near = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 0d);
        long mid = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 2_500d);
        long far = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 5_000d);
        long beyond = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 50_000d);

        assertTrue("가까울수록 짧아야 한다", near < mid);
        assertTrue("멀수록 길어야 한다", mid < far);
        assertEquals("상한을 넘으면 더 길어지지 않는다", far, beyond);
    }

    @Test
    public void flightNeverDropsBelowTheFloor() {
        // fast × 거리 0 이면 1300*0.7 = 910ms 라 바닥값이 걸린다 — 눈이 못 따라가는 이동을 막는다.
        assertEquals(ReplayPlan.FLIGHT_MIN_MS, ReplayPlan.flightMs(MapUiState.Speed.FAST, 0d));
    }

    @Test
    public void fasterPresetFliesShorterAtTheSameDistance() {
        double d = 3_000d;
        assertTrue(ReplayPlan.flightMs(MapUiState.Speed.FAST, d)
                < ReplayPlan.flightMs(MapUiState.Speed.NORMAL, d));
        assertTrue(ReplayPlan.flightMs(MapUiState.Speed.NORMAL, d)
                < ReplayPlan.flightMs(MapUiState.Speed.RELAXED, d));
    }

    @Test
    public void cinemaRestsCloser() {
        assertTrue("상영 모드는 사진에 더 붙어야 한다",
                ReplayPlan.restZoom(true) > ReplayPlan.restZoom(false));
    }

    @Test
    public void distanceMatchesKnownParisLandmarks() {
        // 개선문(48.8738, 2.2950) ↔ 에펠탑(48.8584, 2.2945) 은 실제로 약 1.7km 다.
        double meters = ReplayPlan.distanceMeters(48.8738, 2.2950, 48.8584, 2.2945);

        assertEquals(1_715d, meters, 60d);
    }

    @Test
    public void distanceOfTheSamePointIsZero() {
        assertEquals(0d, ReplayPlan.distanceMeters(48.86, 2.29, 48.86, 2.29), 0.001d);
    }
}
