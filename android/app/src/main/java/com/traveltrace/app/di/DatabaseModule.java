package com.traveltrace.app.di;

import android.content.Context;

import androidx.room.Room;

import com.traveltrace.app.data.db.Migrations;
import com.traveltrace.app.data.db.TravelTraceDatabase;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public final class DatabaseModule {

    private DatabaseModule() {}

    /**
     * 실 마이그레이션을 우선 태우고, 커버되지 않는 개발 중 스키마 점프만 파괴적으로 처리한다.
     * 저장된 여행은 사용자가 되돌릴 수 없는 데이터(재분석 = 비용)라 v1→v2 는 반드시
     * 가산 마이그레이션으로 넘어가야 한다.
     */
    @Provides
    @Singleton
    public static TravelTraceDatabase provideDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, TravelTraceDatabase.class, TravelTraceDatabase.NAME)
                .addMigrations(Migrations.MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build();
    }
}
