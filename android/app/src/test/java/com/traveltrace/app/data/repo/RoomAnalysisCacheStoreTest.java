package com.traveltrace.app.data.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RoomAnalysisCacheStoreTest {

    private TravelTraceDatabase db;
    private RoomAnalysisCacheStore store;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        store = new RoomAnalysisCacheStore(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static PhotoAnalysis gps(long id, String hash) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = "a.jpg";
        a.contentHash = hash;
        a.takenAtUtc = 1_718_154_720_000L;
        a.takenAtHasOffset = true;
        a.lat = 48.8584;
        a.lng = 2.2945;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private static PhotoAnalysis unknown(long id, String hash) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = "b.jpg";
        a.contentHash = hash;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    @Test
    public void storedGpsResultComesBackWithItsCoordinatesAndTime() {
        store.put(gps(11L, "hash-a"));

        PhotoAnalysis hit = store.get(11L, "hash-a");

        assertNotNull(hit);
        assertEquals(48.8584, hit.lat, 0.0001);
        assertEquals(2.2945, hit.lng, 0.0001);
        assertEquals(LocationSource.GPS, hit.source);
        assertEquals(LocationClassification.PLACED, hit.classification);
        assertEquals(Long.valueOf(1_718_154_720_000L), hit.takenAtUtc);
        assertEquals("키의 절반은 되돌려줘야 저장 경로가 그대로 이어 쓸 수 있다",
                "hash-a", hit.contentHash);
    }

    @Test
    public void aMissReturnsNull() {
        assertNull(store.get(11L, "never-seen"));
    }

    @Test
    public void unknownResultsAreNotCached() {
        store.put(unknown(12L, "hash-b"));

        assertNull("UNKNOWN 은 '위치가 없다'가 아니라 '아직 AI 를 안 돌렸다'는 뜻이다 — "
                        + "캐시하면 S3 이 붙어도 이 사진은 영원히 AI 로 못 간다",
                store.get(12L, "hash-b"));
    }

    @Test
    public void aResultWithoutAHashIsNotCached() {
        PhotoAnalysis noHash = gps(13L, null);

        store.put(noHash);

        assertEquals("해시를 못 구한 사진은 키가 없다 — 조용히 건너뛴다", 0,
                db.analysisCacheDao().count());
    }

    @Test
    public void theSamePhotoHitsFromADifferentTripBecauseTheCacheIsTripIndependent() {
        // 여행 A 의 분석 결과를 캐시에 넣고, 여행 B 가 같은 사진(같은 _ID+해시)을 조회한다.
        // 캐시가 여행을 키에 넣지 않는다는 사실 자체가 이 테스트의 대상이다.
        store.put(gps(11L, "hash-a"));

        PhotoAnalysis fromAnotherTrip = store.get(11L, "hash-a");

        assertNotNull("여행이 달라도 같은 사진이면 hit 이어야 한다 (PRD §4.7)", fromAnotherTrip);
        assertEquals(11L, fromAnotherTrip.mediaStoreId);
    }
}
