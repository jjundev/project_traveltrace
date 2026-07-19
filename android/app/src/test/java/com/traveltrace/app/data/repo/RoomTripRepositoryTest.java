package com.traveltrace.app.data.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.model.PhotoAnalysis;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RoomTripRepositoryTest {

    private TravelTraceDatabase db;
    private AppExecutors executors;
    private RoomTripRepository tripRepo;
    private RoomPhotoAnalysisRepository analysisRepo;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        executors = new AppExecutors();
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
        a.takenAtUtc = null;
        a.takenAtHasOffset = false;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    /**
     * {@link AsyncTestHarness#awaitCallback} 로 위임한다 — 콜백이 메인 루퍼에서 오는지까지
     * 검증하는 폴링 로직은 여러 테스트 클래스가 공유하므로 여기서 다시 구현하지 않는다. 이유는
     * {@link AsyncTestHarness} 자바독 참고.
     */
    private static <T> T await(java.util.function.Consumer<com.traveltrace.app.domain.Callback<T>> call) {
        return AsyncTestHarness.awaitCallback(call);
    }

    @Test
    public void savedTripIsListedAndReopenableWithoutReanalysis() {
        List<PhotoAnalysis> results = Arrays.asList(
                placed(2L, "b.jpg", 2_000L, 48.8584, 2.2945),
                placed(1L, "a.jpg", 1_000L, 48.8606, 2.3376),
                unknown(3L, "c.jpg"));

        String tripId = await(cb ->
                analysisRepo.saveTrip("2024 6월 여행", "Europe/Paris", results, cb));

        assertNotNull("저장은 tripId 를 돌려줘야 한다", tripId);

        List<TripSummary> list = await(cb -> tripRepo.list(cb));
        assertEquals(1, list.size());
        assertEquals("2024 6월 여행", list.get(0).name);
        assertEquals("사진 3장이 전부 집계되어야 한다", 3, list.get(0).photoCount);
        assertEquals("hero 는 가장 이른 사진", Long.valueOf(1L), list.get(0).heroMediaStoreId);

        TripDetail detail = await(cb -> tripRepo.open(tripId, cb));
        assertNotNull(detail);
        assertEquals("Europe/Paris", detail.timeZoneId);
        assertEquals("PLACED 2장만 경로에 오른다", 2, detail.stops.size());
        assertEquals("시각순 정렬", 1L, detail.stops.get(0).mediaStoreId);
        assertEquals("위치 미상 1장", 1, detail.unknownCount);
    }

    @Test
    public void openingAnUnknownTripIdYieldsNull() {
        TripDetail detail = await(cb -> tripRepo.open("nope", cb));
        assertNull(detail);
    }

    @Test
    public void deleteRemovesTheTripFromTheList() {
        String tripId = await(cb -> analysisRepo.saveTrip(
                "지울 여행", "Asia/Seoul",
                new ArrayList<>(Arrays.asList(placed(1L, "a.jpg", 1_000L, 37.5, 127.0))), cb));

        // await(...) 가 대입 등 타입 문맥 없이 쓰이면(구문 자체이거나 메서드 호출의 수신자)
        // javac 가 암시적 람다의 타입 인자를 Object 로 잡아 컴파일에 실패한다(JLS 18.5.2) —
        // 지역 변수 대입으로 타입 문맥을 줘서 우회한다.
        Void ignored = await(cb -> tripRepo.delete(tripId, cb));

        List<TripSummary> afterDelete = await(cb -> tripRepo.list(cb));
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    public void tripWithNoPlacedPhotosStillSavesAndOpensWithEmptyStops() {
        String tripId = await(cb -> analysisRepo.saveTrip(
                "전부 미상", "Asia/Seoul",
                new ArrayList<>(Arrays.asList(unknown(1L, "a.jpg"))), cb));

        TripDetail detail = await(cb -> tripRepo.open(tripId, cb));
        assertNotNull(detail);
        assertTrue("좌표 없는 사진은 스톱이 되지 않는다", detail.stops.isEmpty());
        assertEquals(1, detail.unknownCount);
    }
}
