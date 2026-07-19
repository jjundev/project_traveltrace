package com.traveltrace.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.domain.model.StopRow;

import java.util.List;

@Dao
public interface PhotoLocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<PhotoLocationEntity> locations);

    /**
     * 경로에 그릴 정차 지점. PLACED 이고 빼지 않은 것만, 촬영 시각 오름차순.
     * 동일 초(연사)는 displayName 으로 안정 tiebreak (plan/06).
     */
    @Query("SELECT p.id AS photoId, p.mediaStoreId AS mediaStoreId, "
            + "p.takenAtUtc AS takenAtUtc, p.displayName AS displayName, "
            + "l.lat AS lat, l.lng AS lng, l.source AS source, "
            + "l.landmarkName AS landmarkName "
            + "FROM photos p INNER JOIN photo_locations l ON l.photoId = p.id "
            + "WHERE p.tripId = :tripId AND l.classification = 'PLACED' AND l.detached = 0 "
            + "ORDER BY p.takenAtUtc ASC, p.displayName ASC")
    List<StopRow> stopsFor(String tripId);

    @Query("SELECT COUNT(*) FROM photos p INNER JOIN photo_locations l ON l.photoId = p.id "
            + "WHERE p.tripId = :tripId AND l.classification = :classification")
    int countByClassification(String tripId, LocationClassification classification);
}
