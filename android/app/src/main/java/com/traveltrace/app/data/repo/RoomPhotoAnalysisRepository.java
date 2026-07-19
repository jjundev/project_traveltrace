package com.traveltrace.app.data.repo;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.PhotoEntity;
import com.traveltrace.app.data.db.PhotoLocationEntity;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.db.TripEntity;
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RoomPhotoAnalysisRepository implements PhotoAnalysisRepository {

    private final TravelTraceDatabase db;
    private final AppExecutors executors;

    @Inject
    public RoomPhotoAnalysisRepository(TravelTraceDatabase db, AppExecutors executors) {
        this.db = db;
        this.executors = executors;
    }

    @Override
    public void saveTrip(String name, String timeZoneId, List<PhotoAnalysis> results,
                         Callback<String> callback) {
        executors.io().execute(() -> {
            String tripId = UUID.randomUUID().toString();

            List<PhotoEntity> photos = new ArrayList<>();
            List<PhotoLocationEntity> locations = new ArrayList<>();
            long earliest = Long.MAX_VALUE;
            long latest = Long.MIN_VALUE;

            for (PhotoAnalysis a : results) {
                String photoId = tripId + ":" + a.mediaStoreId;

                PhotoEntity p = new PhotoEntity();
                p.id = photoId;
                p.tripId = tripId;
                p.mediaStoreId = a.mediaStoreId;
                p.takenAtUtc = a.takenAtUtc;
                p.takenAtHasOffset = a.takenAtHasOffset;
                p.displayName = a.displayName;
                photos.add(p);

                PhotoLocationEntity l = new PhotoLocationEntity();
                l.photoId = photoId;
                l.lat = a.lat;
                l.lng = a.lng;
                l.source = a.source;
                l.classification = a.classification;
                l.detached = false;
                locations.add(l);

                if (a.takenAtUtc != null) {
                    earliest = Math.min(earliest, a.takenAtUtc);
                    latest = Math.max(latest, a.takenAtUtc);
                }
            }

            long now = System.currentTimeMillis();
            boolean hasTimes = earliest != Long.MAX_VALUE;

            TripEntity trip = new TripEntity();
            trip.id = tripId;
            trip.name = name;
            trip.photoCount = results.size();
            trip.startDateUtc = hasTimes ? earliest : now;
            trip.endDateUtc = hasTimes ? latest : now;
            trip.dayCount = dayCount(trip.startDateUtc, trip.endDateUtc);
            trip.timeZoneId = timeZoneId;
            trip.createdAt = now;
            trip.costSpent = 0d;

            db.runInTransaction(() -> {
                db.tripDao().insert(trip);
                db.photoDao().insertAll(photos);
                db.photoLocationDao().insertAll(locations);
            });

            executors.mainThread().execute(() -> callback.onResult(tripId));
        });
    }

    /** 시작·종료가 같은 날이어도 1일. 경계 정밀도는 여행 타임존 도입(S4) 전까지 근사로 둔다. */
    private static int dayCount(long startUtc, long endUtc) {
        long span = Math.max(0L, endUtc - startUtc);
        return (int) (TimeUnit.MILLISECONDS.toDays(span) + 1);
    }
}
