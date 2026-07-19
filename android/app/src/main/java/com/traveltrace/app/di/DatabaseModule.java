package com.traveltrace.app.di;

import android.content.Context;

import androidx.room.Room;

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
     * 정식 릴리스 전까지 파괴적 마이그레이션을 허용한다 — 아직 배포된 버전이 없어
     * 보존할 사용자 데이터가 없다. 첫 릴리스 시점에 실제 Migration 으로 교체한다.
     */
    @Provides
    @Singleton
    public static TravelTraceDatabase provideDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, TravelTraceDatabase.class, TravelTraceDatabase.NAME)
                .fallbackToDestructiveMigration()
                .build();
    }
}
