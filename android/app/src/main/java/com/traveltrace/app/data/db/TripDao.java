package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

@Dao
public interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TripEntity trip);

    /**
     * HOME 목록. hero 는 가장 이른 촬영 시각의 사진 — 시각이 없는 사진은 뒤로 밀린다
     * (SQLite 는 NULL 을 가장 작은 값으로 정렬하므로 IS NULL 을 먼저 태운다).
     */
    @Query("SELECT t.id AS id, t.name AS name, t.photoCount AS photoCount, "
            + "t.dayCount AS dayCount, t.startDateUtc AS startDateUtc, "
            + "t.timeZoneId AS timeZoneId, "
            + "(SELECT p.mediaStoreId FROM photos p WHERE p.tripId = t.id "
            + " ORDER BY (p.takenAtUtc IS NULL), p.takenAtUtc ASC, p.displayName ASC LIMIT 1) "
            + "AS heroMediaStoreId "
            + "FROM trips t ORDER BY t.createdAt DESC")
    List<TripSummary> listSummaries();

    @Nullable
    @Query("SELECT * FROM trips WHERE id = :id")
    TripEntity findById(String id);

    @Query("DELETE FROM trips WHERE id = :id")
    void deleteById(String id);
}
