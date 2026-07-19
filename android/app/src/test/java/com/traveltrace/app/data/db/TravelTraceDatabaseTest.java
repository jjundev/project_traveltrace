package com.traveltrace.app.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.domain.model.StopRow;
import com.traveltrace.app.domain.model.TripSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TravelTraceDatabaseTest {

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

    private static TripEntity trip(String id) {
        TripEntity t = new TripEntity();
        t.id = id;
        t.name = "2024 6월 여행";
        t.photoCount = 2;
        t.dayCount = 1;
        t.startDateUtc = 1_718_000_000_000L;
        t.endDateUtc = 1_718_080_000_000L;
        t.timeZoneId = "Asia/Seoul";
        t.createdAt = 1_718_090_000_000L;
        return t;
    }

    private static PhotoEntity photo(String id, String tripId, long mediaStoreId,
                                     Long takenAtUtc, String displayName) {
        PhotoEntity p = new PhotoEntity();
        p.id = id;
        p.tripId = tripId;
        p.mediaStoreId = mediaStoreId;
        p.takenAtUtc = takenAtUtc;
        p.takenAtHasOffset = true;
        p.displayName = displayName;
        return p;
    }

    private static PhotoLocationEntity placed(String photoId, double lat, double lng) {
        PhotoLocationEntity l = new PhotoLocationEntity();
        l.photoId = photoId;
        l.lat = lat;
        l.lng = lng;
        l.source = LocationSource.GPS;
        l.classification = LocationClassification.PLACED;
        l.detached = false;
        return l;
    }

    @Test
    public void tripRoundTripsAndSummaryCarriesTheHeroPhoto() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Arrays.asList(
                photo("p2", "t1", 222L, 2_000L, "b.jpg"),
                photo("p1", "t1", 111L, 1_000L, "a.jpg")));

        List<TripSummary> summaries = db.tripDao().listSummaries();

        assertEquals(1, summaries.size());
        TripSummary s = summaries.get(0);
        assertEquals("t1", s.id);
        assertEquals("2024 6월 여행", s.name);
        assertEquals(2, s.photoCount);
        assertEquals("hero 는 가장 이른 촬영 시각의 사진이어야 한다",
                Long.valueOf(111L), s.heroMediaStoreId);
    }

    @Test
    public void stopsAreOrderedByTakenAtAndExcludeNonPlaced() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Arrays.asList(
                photo("late", "t1", 2L, 2_000L, "late.jpg"),
                photo("early", "t1", 1L, 1_000L, "early.jpg"),
                photo("nogps", "t1", 3L, 1_500L, "nogps.jpg")));

        PhotoLocationEntity unknown = new PhotoLocationEntity();
        unknown.photoId = "nogps";
        unknown.source = LocationSource.NONE;
        unknown.classification = LocationClassification.UNKNOWN;
        unknown.detached = false;

        db.photoLocationDao().insertAll(Arrays.asList(
                placed("late", 48.86, 2.35),
                placed("early", 48.85, 2.29),
                unknown));

        List<StopRow> stops = db.photoLocationDao().stopsFor("t1");

        assertEquals("PLACED 만 경로에 들어간다", 2, stops.size());
        assertEquals("early", stops.get(0).photoId);
        assertEquals("late", stops.get(1).photoId);
        assertNotNull(stops.get(0).lat);
    }

    @Test
    public void unknownCountIsQueryable() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Collections.singletonList(
                photo("nogps", "t1", 3L, null, "nogps.jpg")));

        PhotoLocationEntity unknown = new PhotoLocationEntity();
        unknown.photoId = "nogps";
        unknown.source = LocationSource.NONE;
        unknown.classification = LocationClassification.UNKNOWN;
        unknown.detached = false;
        db.photoLocationDao().insertAll(Collections.singletonList(unknown));

        assertEquals(1, db.photoLocationDao()
                .countByClassification("t1", LocationClassification.UNKNOWN));
    }

    @Test
    public void deletingATripCascadesToPhotosAndLocations() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Collections.singletonList(
                photo("p1", "t1", 1L, 1_000L, "a.jpg")));
        db.photoLocationDao().insertAll(Collections.singletonList(placed("p1", 48.85, 2.29)));

        db.tripDao().deleteById("t1");

        assertNull(db.tripDao().findById("t1"));
        assertEquals(0, db.photoLocationDao().stopsFor("t1").size());
        assertEquals("여행이 지워지면 사진 행도 남지 않는다",
                0, db.photoDao().countForTrip("t1"));
    }
}
