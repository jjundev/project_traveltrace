package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.maps.model.LatLngBounds;

import com.traveltrace.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class MapRouteRendererTest {

    private static MapUiState.Stop stop(String id, double lat, double lng) {
        return new MapUiState.Stop(id, id, "10:00", false, 0, 0xFFCCCCCC, lat, lng);
    }

    @Test
    public void boundsCoverEveryStop() {
        List<MapUiState.Stop> stops = Arrays.asList(
                stop("a", 48.8584, 2.2945),
                stop("b", 48.8606, 2.3376));

        LatLngBounds bounds = MapRouteRenderer.boundsOf(stops);

        assertNotNull(bounds);
        assertEquals(48.8584, bounds.southwest.latitude, 0.0001);
        assertEquals(2.3376, bounds.northeast.longitude, 0.0001);
    }

    @Test
    public void singleStopHasNoBoundsSoTheCallerUsesAFixedZoom() {
        assertNull("스톱 1개면 span 이 0이라 bounds fit 이 과도 줌/예외를 낸다",
                MapRouteRenderer.boundsOf(Collections.singletonList(stop("a", 48.85, 2.29))));
    }

    @Test
    public void noStopsHasNoBounds() {
        assertNull(MapRouteRenderer.boundsOf(new ArrayList<>()));
    }

    @Test
    public void identicalCoordinatesAreTreatedAsASinglePoint() {
        List<MapUiState.Stop> sameSpot = Arrays.asList(
                stop("a", 48.85, 2.29),
                stop("b", 48.85, 2.29));

        assertNull("좌표가 전부 같으면 span 이 0 — bounds 대신 고정 줌을 써야 한다",
                MapRouteRenderer.boundsOf(sameSpot));
    }

    private static MapUiState.Stop stop(double lat, double lng, boolean ai) {
        return new MapUiState.Stop("p" + lat, "이름", "09:00", ai, 0, 0xFFEEEEEE, lat, lng);
    }

    @Test
    public void aiStopsGetTheApproximatePin() {
        assertEquals(R.drawable.pin_approx, MapRouteRenderer.pinResFor(true));
        assertEquals(R.drawable.pin_gps, MapRouteRenderer.pinResFor(false));
    }

    @Test
    public void segmentTouchingAnAiStopIsDashed() {
        boolean[] dashed = MapRouteRenderer.dashedSegments(Arrays.asList(
                stop(1, 1, false), stop(2, 2, true), stop(3, 3, false)));
        assertEquals(2, dashed.length);
        assertTrue("AI 스톱으로 들어가는 구간은 추정 경로다", dashed[0]);
        assertTrue("AI 스톱에서 나가는 구간도 마찬가지다", dashed[1]);
    }

    @Test
    public void segmentBetweenTwoGpsStopsIsSolid() {
        boolean[] dashed = MapRouteRenderer.dashedSegments(Arrays.asList(
                stop(1, 1, false), stop(2, 2, false)));
        assertEquals(1, dashed.length);
        assertFalse("GPS 끼리는 실측 경로다 — 점선으로 그리면 정확도를 스스로 깎는다", dashed[0]);
    }

    @Test
    public void singleStopHasNoSegments() {
        assertEquals(0, MapRouteRenderer.dashedSegments(
                Collections.singletonList(stop(1, 1, true))).length);
    }

    @Test
    public void emptyStopsHaveNoSegments() {
        assertEquals(0, MapRouteRenderer.dashedSegments(
                Collections.<MapUiState.Stop>emptyList()).length);
    }
}
