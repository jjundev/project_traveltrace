package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.SavedStateHandle;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelLoadTest {

    private AppExecutors executors;
    private TravelTraceDatabase db;
    private RoomTripRepository tripRepo;
    private RoomPhotoAnalysisRepository analysisRepo;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        tripRepo = new RoomTripRepository(db, executors);
        analysisRepo = new RoomPhotoAnalysisRepository(db, executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    private static PhotoAnalysis placed(long id, String name, long takenAt,
                                        double lat, double lng) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = takenAt;
        a.takenAtHasOffset = true;
        a.lat = lat;
        a.lng = lng;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private static PhotoAnalysis unknown(long id, String name) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    // 브리프 원본은 트리거 직후 ShadowLooper.idleMainLooper() 를 한 번만 불렀지만, saveTrip/open
    // 모두 진짜 백그라운드 스레드풀(AppExecutors.io())에서 실행되고 메인 루퍼로 결과를 포스팅하므로
    // 단발 idle 호출은 io 스레드와 경쟁해 간헐적으로 실패한다(AsyncTestHarness 클래스 자바독 참고).
    // 그래서 폴링 헬퍼로 대체한다 — 검증 대상(순서·필터링·타임존 포맷)은 그대로다.
    private String saveTrip() {
        return AsyncTestHarness.awaitCallback(cb ->
                analysisRepo.saveTrip("2024년 6월 여행", "Europe/Paris", Arrays.asList(
                        placed(2L, "b.jpg", 1_718_158_320_000L, 48.8606, 2.3376),
                        placed(1L, "a.jpg", 1_718_154_720_000L, 48.8584, 2.2945),
                        unknown(3L, "c.jpg")), cb));
    }

    private MapUiState load(String tripId) {
        SavedStateHandle handle = new SavedStateHandle();
        if (tripId != null) handle.set(MapReplayFragment.ARG_TRIP_ID, tripId);
        MapReplayViewModel vm = new MapReplayViewModel(handle, tripRepo);
        return AsyncTestHarness.awaitLiveData(
                vm.state(), vm::load, state -> state != null, "MapReplayViewModel.load()");
    }

    @Test
    public void savedTripRendersItsStopsInTimeOrder() {
        MapUiState state = load(saveTrip());

        assertEquals("2024년 6월 여행", state.tripTitle);
        assertEquals("PLACED 2장만 스톱이 된다", 2, state.stops.size());
        assertEquals("a.jpg", state.stops.get(0).name);
        assertEquals(48.8584, state.stops.get(0).lat, 0.0001);
        assertEquals("위치 미상 1장", 1, state.unknownCount);
    }

    @Test
    public void stopsCarryFormattedLocalTime() {
        MapUiState state = load(saveTrip());

        assertTrue("시각은 여행 타임존 기준 HH:mm 이어야 한다: " + state.stops.get(0).time,
                state.stops.get(0).time.matches("\\d{2}:\\d{2}"));
    }

    @Test
    public void withoutATripIdTheFixtureMapIsShown() {
        MapUiState state = load(null);

        assertEquals("인자 없이 진입하면 디자인 프리뷰가 뜬다", "2024 파리 여행", state.tripTitle);
        assertEquals(6, state.stops.size());
    }

    @Test
    public void unknownTripIdYieldsAnEmptyRouteWithoutCrashing() {
        MapUiState state = load("does-not-exist");

        assertTrue(state.stops.isEmpty());
        assertEquals(0, state.unknownCount);
    }
}
