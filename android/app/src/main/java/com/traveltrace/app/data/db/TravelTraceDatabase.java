package com.traveltrace.app.data.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

/**
 * v2. S1 이 세운 trips/photos/photo_locations 위에 S8 이 analysis_cache 를 가산으로 얹었다
 * ({@link Migrations#MIGRATION_1_2}). 캐시는 여행과 독립이라 여행 삭제에 딸려가지 않는다.
 */
@Database(
        entities = {
                TripEntity.class,
                PhotoEntity.class,
                PhotoLocationEntity.class,
                AnalysisCacheEntity.class},
        version = TravelTraceDatabase.VERSION,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class TravelTraceDatabase extends RoomDatabase {

    public static final String NAME = "traveltrace.db";
    public static final int VERSION = 2;

    public abstract TripDao tripDao();

    public abstract PhotoDao photoDao();

    public abstract PhotoLocationDao photoLocationDao();

    public abstract AnalysisCacheDao analysisCacheDao();
}
