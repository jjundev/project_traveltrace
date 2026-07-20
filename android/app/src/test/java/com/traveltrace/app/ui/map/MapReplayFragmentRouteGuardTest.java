package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link MapReplayFragment#sameRoute} 는 렌더 가드의 핵심 판단이다 — GoogleMap 을 전혀
 * 부르지 않는 순수 predicate 라, Maps SDK shadow 없이도 "재생/속도/위성 전환에 그리기와
 * 카메라 이동을 건너뛴다"는 동작을 직접 검증할 수 있다.
 */
public class MapReplayFragmentRouteGuardTest {

    private static MapUiState.Stop stop(String id, double lat, double lng) {
        return new MapUiState.Stop(id, id, "10:00", false, 0, 0xFFCCCCCC, lat, lng, null);
    }

    @Test
    public void sameUnderlyingStopInstancesAreTheSameRoute() {
        // togglePlay/setSpeed 같은 UI-only emission 이 실제로 하는 일: 같은 Stop 인스턴스를 새 리스트에 담아 넘긴다.
        MapUiState.Stop a = stop("a", 48.85, 2.29);
        MapUiState.Stop b = stop("b", 48.86, 2.30);
        List<MapUiState.Stop> drawn = Arrays.asList(a, b);
        List<MapUiState.Stop> nextEmission = new ArrayList<>(Arrays.asList(a, b));

        assertTrue("같은 Stop 인스턴스면 togglePlay/setSpeed 같은 UI-only emission — 다시 그리면 안 된다",
                MapReplayFragment.sameRoute(drawn, nextEmission));
    }

    @Test
    public void freshlyBuiltStopInstancesAreADifferentRoute() {
        // toState() 가 여행을 새로 열 때 하는 일: 좌표가 같아도 완전히 새 Stop 객체를 만든다.
        List<MapUiState.Stop> drawn = Collections.singletonList(stop("a", 48.85, 2.29));
        List<MapUiState.Stop> reopened = Collections.singletonList(stop("a", 48.85, 2.29));

        assertFalse("좌표가 같아도 Stop 인스턴스가 다르면 다른 여행을 새로 연 것 — 다시 그려야 한다",
                MapReplayFragment.sameRoute(drawn, reopened));
    }

    @Test
    public void differentSizeIsADifferentRoute() {
        MapUiState.Stop a = stop("a", 48.85, 2.29);
        List<MapUiState.Stop> drawn = Collections.singletonList(a);
        List<MapUiState.Stop> grown = Arrays.asList(a, stop("b", 48.86, 2.30));

        assertFalse(MapReplayFragment.sameRoute(drawn, grown));
    }

    @Test
    public void bothEmptyIsTheSameRoute() {
        // 위치 미상뿐인 여행처럼 스톱이 아예 없는 경우도 반복 emission 에 다시 그리지 않아야 한다.
        assertTrue(MapReplayFragment.sameRoute(new ArrayList<>(), new ArrayList<>()));
    }
}
