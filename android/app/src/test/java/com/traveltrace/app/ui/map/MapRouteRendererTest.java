package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.android.gms.maps.model.LatLngBounds;

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
}
