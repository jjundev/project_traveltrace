package com.traveltrace.app.ui.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.traveltrace.app.ui.analysis.AnalysisUiState;
import com.traveltrace.app.ui.home.HomeUiState;
import com.traveltrace.app.ui.map.MapUiState;
import com.traveltrace.app.ui.photo.PhotoSelectionUiState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** 픽스처가 프로토타입 원본 값과 어긋나면 화면 충실도가 조용히 깨지므로 값을 고정한다. */
public class ScreenFixturesTest {

    @Test
    public void home_hasParisEnabledAndJejuLocked() {
        HomeUiState s = ScreenFixtures.home();
        assertFalse(s.empty);
        assertEquals(2, s.trips.size());

        HomeUiState.TripCard paris = s.trips.get(0);
        assertEquals("paris", paris.id);
        assertEquals("2024 파리 여행", paris.title);
        assertEquals("82장 · 4일 · 2024. 6", paris.meta);
        assertEquals("🇫🇷 파리 · 프랑스", paris.locationLabel);
        assertTrue(paris.enabled);

        HomeUiState.TripCard jeju = s.trips.get(1);
        assertEquals("2023 제주 가족여행", jeju.title);
        assertEquals("63장 · 3일 · 2023. 10", jeju.meta);
        assertFalse("제주 카드는 데모에서 잠김", jeju.enabled);
    }

    @Test
    public void homeEmpty_hasNoTrips() {
        HomeUiState s = ScreenFixtures.homeEmpty();
        assertTrue(s.empty);
        assertTrue(s.trips.isEmpty());
    }

    @Test
    public void trips_withEmptyList_derivesEmptyTrue() {
        HomeUiState s = HomeUiState.trips(Collections.<HomeUiState.TripCard>emptyList());
        assertTrue("빈 리스트로 만든 상태는 empty=true 여야 한다", s.empty);
        assertTrue(s.trips.isEmpty());
    }

    @Test
    public void photoSelection_has18TilesWith16Selected() {
        PhotoSelectionUiState s = ScreenFixtures.photoSelection();
        assertEquals(18, s.tiles.size());
        assertEquals(16, s.selectedCount());
        assertEquals(100, s.maxCount);
        assertEquals("2024. 6. 12 – 6. 15 · 사진 94장", s.periodLabel);

        // index 4(음식) / 11(실내) 만 초기 해제 — 프로토타입 state.grid
        assertFalse(s.tiles.get(4).selected);
        assertFalse(s.tiles.get(11).selected);
        assertEquals("음식", s.tiles.get(4).label);
        assertEquals(0xFFDBE4EE, s.tiles.get(0).toneColor);
        assertNull("라벨 없는 타일은 null", s.tiles.get(0).label);
    }

    @Test
    public void photoSelection_toggleFlipsOneTileImmutably() {
        PhotoSelectionUiState s = ScreenFixtures.photoSelection();
        PhotoSelectionUiState next = s.withToggled(0);

        assertTrue("원본 불변", s.tiles.get(0).selected);
        assertFalse(next.tiles.get(0).selected);
        assertEquals(15, next.selectedCount());
    }

    @Test
    public void analysisInProgress_reportsPercentAgainst82() {
        AnalysisUiState s = ScreenFixtures.analysisInProgress(41);
        assertEquals(41, s.analyzed);
        assertEquals(82, s.total);
        assertFalse(s.done);
        assertEquals(50, s.progressPercent());
    }

    @Test
    public void analysisDone_reportsRouteAndUnknownCounts() {
        AnalysisUiState s = ScreenFixtures.analysisDone();
        assertTrue(s.done);
        assertEquals(82, s.analyzed);
        assertEquals(100, s.progressPercent());
        assertEquals(6, s.routeCount);
        assertEquals(5, s.unknownCount);
    }

    @Test
    public void map_hasSixStopsWithLouvreAsAi() {
        MapUiState s = ScreenFixtures.map();
        assertEquals("2024 파리 여행", s.tripTitle);
        assertEquals(5, s.unknownCount);
        assertEquals(6, s.stops.size());
        assertEquals(0, s.activeIndex);
        assertEquals(MapUiState.Speed.NORMAL, s.speed);

        MapUiState.Stop arc = s.stops.get(0);
        assertEquals("개선문", arc.name);
        assertEquals("10:12", arc.time);
        assertFalse(arc.ai);
        assertEquals(0xFFD9C9A8, arc.toneColor);

        MapUiState.Stop louvre = s.stops.get(3);
        assertEquals("루브르 박물관", louvre.name);
        assertTrue("루브르만 AI 근사 위치", louvre.ai);

        assertEquals(4, s.stops.get(1).extra); // 에펠탑 +4장
        assertEquals("개선문", s.activeStop().name);
    }

    @Test
    public void unknownThumbTones_hasFiveTones() {
        assertEquals(5, ScreenFixtures.unknownThumbTones().length);
        assertEquals(0xFFE3D6C8, ScreenFixtures.unknownThumbTones()[0]);
    }

    @Test
    public void uiStateDoesNotAliasCallerList() {
        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        tiles.add(new PhotoSelectionUiState.Tile(0xFF000000, null, true, 0L, null));
        PhotoSelectionUiState s = new PhotoSelectionUiState("p", 100, tiles);

        tiles.add(new PhotoSelectionUiState.Tile(0xFFFFFFFF, null, true, 0L, null));

        assertEquals("생성 후 호출자가 원본 리스트를 바꿔도 상태는 불변", 1, s.tiles.size());
    }
}
