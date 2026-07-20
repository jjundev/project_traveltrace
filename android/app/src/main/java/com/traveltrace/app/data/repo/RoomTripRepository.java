package com.traveltrace.app.data.repo;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.db.TripEntity;
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RoomTripRepository implements TripRepository {

    private final TravelTraceDatabase db;
    private final AppExecutors executors;

    @Inject
    public RoomTripRepository(TravelTraceDatabase db, AppExecutors executors) {
        this.db = db;
        this.executors = executors;
    }

    @Override
    public void list(Callback<List<TripSummary>> callback) {
        executors.io().execute(() -> {
            List<TripSummary> summaries = db.tripDao().listSummaries();
            executors.mainThread().execute(() -> callback.onResult(summaries));
        });
    }

    @Override
    public void open(String tripId, Callback<TripDetail> callback) {
        executors.io().execute(() -> {
            TripEntity entity = db.tripDao().findById(tripId);
            TripDetail detail;
            if (entity == null) {
                detail = null;
            } else {
                detail = new TripDetail();
                detail.id = entity.id;
                detail.name = entity.name;
                detail.timeZoneId = entity.timeZoneId;
                detail.stops = db.photoLocationDao().stopsFor(tripId);
                // NAME_ONLY 는 "지도에 없는 사진"이라는 점에서 사용자에겐 위치 미상과 같다.
                // 둘을 화면에서 분리하는 건 S6 이며, 그때 이 합산을 쪼갠다.
                detail.unknownCount = db.photoLocationDao()
                        .countByClassification(tripId, LocationClassification.UNKNOWN)
                        + db.photoLocationDao()
                        .countByClassification(tripId, LocationClassification.NAME_ONLY);
            }
            TripDetail result = detail;
            executors.mainThread().execute(() -> callback.onResult(result));
        });
    }

    @Override
    public void delete(String tripId, Callback<Void> callback) {
        executors.io().execute(() -> {
            db.tripDao().deleteById(tripId);
            executors.mainThread().execute(() -> callback.onResult(null));
        });
    }
}
