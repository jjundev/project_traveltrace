package com.traveltrace.app.data.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

/**
 * v1. AnalysisCache((_ID+해시) 캐시)는 AI 호출을 막는 장치라 S1 에는 없다 — S4/S8 에서
 * 테이블 추가(가산 마이그레이션)로 들어온다.
 */
@Database(
        entities = {TripEntity.class, PhotoEntity.class, PhotoLocationEntity.class},
        version = 1,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class TravelTraceDatabase extends RoomDatabase {

    public static final String NAME = "traveltrace.db";

    public abstract TripDao tripDao();

    public abstract PhotoDao photoDao();

    public abstract PhotoLocationDao photoLocationDao();
}
