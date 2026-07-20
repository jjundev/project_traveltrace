package com.traveltrace.app.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnalysisCacheDaoTest {

    private TravelTraceDatabase db;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static AnalysisCacheEntity entry(long mediaStoreId, String hash, double lat) {
        AnalysisCacheEntity e = new AnalysisCacheEntity();
        e.mediaStoreId = mediaStoreId;
        e.contentHash = hash;
        e.lat = lat;
        e.lng = 2.2945;
        e.source = LocationSource.GPS;
        e.classification = LocationClassification.PLACED;
        e.takenAtUtc = 1_718_154_720_000L;
        e.takenAtHasOffset = true;
        e.createdAt = 1_718_200_000_000L;
        return e;
    }

    @Test
    public void entryRoundTripsByIdAndHash() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        AnalysisCacheEntity found = db.analysisCacheDao().find(11L, "hash-a");

        assertNotNull(found);
        assertEquals(48.8584, found.lat, 0.0001);
        assertEquals(LocationSource.GPS, found.source);
        assertEquals(LocationClassification.PLACED, found.classification);
        assertEquals(Long.valueOf(1_718_154_720_000L), found.takenAtUtc);
    }

    @Test
    public void aDifferentHashOnTheSameIdIsAMiss() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        assertNull("사진이 편집되어 내용이 바뀌면 옛 결과를 재사용하면 안 된다",
                db.analysisCacheDao().find(11L, "hash-b"));
    }

    @Test
    public void theSameIdAndHashUpsertsInsteadOfDuplicating() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 37.5665));

        assertEquals("(_ID+해시) 는 복합 PK 다 — 행이 늘어나면 안 된다", 1,
                db.analysisCacheDao().count());
        assertEquals(37.5665, db.analysisCacheDao().find(11L, "hash-a").lat, 0.0001);
    }

    @Test
    public void cacheSurvivesTripDeletionBecauseItIsTripIndependent() {
        TripEntity trip = new TripEntity();
        trip.id = "t1";
        trip.name = "지울 여행";
        trip.timeZoneId = "Asia/Seoul";
        trip.createdAt = 1L;
        db.tripDao().insert(trip);
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        db.tripDao().deleteById("t1");

        assertNotNull("여행을 지워도 캐시는 남아야 한다 — 다음 여행이 그걸 재사용한다",
                db.analysisCacheDao().find(11L, "hash-a"));
    }
}
