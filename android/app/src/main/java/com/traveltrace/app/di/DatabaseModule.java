package com.traveltrace.app.di;

import android.content.Context;

import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.traveltrace.app.BuildConfig;
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
     * 등록된 실 마이그레이션(Migrations.ALL)을 우선 태우고, 커버되지 않는 스키마 점프는
     * 빌드 종류에 따라 다르게 처리한다({@link #applyFallbackPolicy}).
     *
     * <p>저장된 여행은 사용자가 되돌릴 수 없는 데이터(재분석 = 비용)라, 릴리스에서는
     * forward 마이그레이션 누락을 절대 조용한 삭제로 넘기지 않는다.
     */
    @Provides
    @Singleton
    public static TravelTraceDatabase provideDatabase(@ApplicationContext Context context) {
        RoomDatabase.Builder<TravelTraceDatabase> builder =
                Room.databaseBuilder(context, TravelTraceDatabase.class, TravelTraceDatabase.NAME)
                        .addMigrations(Migrations.ALL);
        applyFallbackPolicy(builder, BuildConfig.DEBUG);
        return builder.build();
    }

    /**
     * 마이그레이션으로 커버되지 않는 스키마 점프의 폴백 정책을 적용한다.
     *
     * <p>BuildConfig.DEBUG 는 유닛 테스트에서 항상 true 라 release 분기를 직접 못 탄다 —
     * 그래서 정책 결정을 이 헬퍼로 뽑아 boolean 을 받게 하고, 테스트가 두 분기를 모두
     * 검증할 수 있게 한다(DatabaseModuleTest).
     *
     * @param debug true(개발)면 어떤 미커버 점프든 DB 재생성으로 넘겨 반복을 빠르게 한다.
     *              false(릴리스)면 다운그레이드만 파괴적으로 허용하고, 누락된 forward
     *              마이그레이션은 IllegalStateException 으로 크게 실패시킨다 — 사용자
     *              데이터가 조용히 삭제되는 경로를 없앤다.
     */
    static void applyFallbackPolicy(RoomDatabase.Builder<TravelTraceDatabase> builder,
                                    boolean debug) {
        if (debug) {
            builder.fallbackToDestructiveMigration();
        } else {
            builder.fallbackToDestructiveMigrationOnDowngrade();
        }
    }
}
