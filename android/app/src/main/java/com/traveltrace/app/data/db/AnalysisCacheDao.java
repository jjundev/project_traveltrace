package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface AnalysisCacheDao {

    /** hit 이면 AI·지오코딩·EXIF 재파싱을 전부 건너뛴다(PRD §4.7). */
    @Nullable
    @Query("SELECT * FROM analysis_cache WHERE mediaStoreId = :mediaStoreId "
            + "AND contentHash = :contentHash")
    AnalysisCacheEntity find(long mediaStoreId, String contentHash);

    /** 복합 PK 라 같은 (_ID+해시) 는 행이 늘지 않고 덮어써진다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AnalysisCacheEntity entry);

    @Query("SELECT COUNT(*) FROM analysis_cache")
    int count();
}
