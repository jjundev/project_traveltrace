package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * 스키마 마이그레이션 모음. 전부 <b>가산</b>이어야 한다 — 저장된 여행은 사용자가 다시 만들
 * 수 없는 데이터라(사진은 그대로여도 분석 결과는 재분석 비용이다) 파괴적 재생성으로 지우면
 * 안 된다.
 */
public final class Migrations {

    private Migrations() {}

    /** v2: analysis_cache 추가. 기존 3개 테이블은 손대지 않는다. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `analysis_cache` ("
                    + "`mediaStoreId` INTEGER NOT NULL, "
                    + "`contentHash` TEXT NOT NULL, "
                    + "`lat` REAL, "
                    + "`lng` REAL, "
                    + "`source` TEXT NOT NULL, "
                    + "`landmarkName` TEXT, "
                    + "`city` TEXT, "
                    + "`country` TEXT, "
                    + "`classification` TEXT NOT NULL, "
                    + "`confidence` REAL, "
                    + "`takenAtUtc` INTEGER, "
                    + "`takenAtHasOffset` INTEGER NOT NULL, "
                    + "`model` TEXT, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`mediaStoreId`, `contentHash`))");
        }
    };

    /**
     * databaseBuilder 에 등록할 모든 마이그레이션의 단일 출처. 새 마이그레이션을 만들면
     * 반드시 여기에 더한다 — 빠뜨리면 release 빌드에서 그 점프가 데이터 삭제 대신 크게
     * 실패하고(정상 방향), DatabaseModuleTest 의 체인 가드가 CI 에서 먼저 잡는다.
     */
    public static final Migration[] ALL = { MIGRATION_1_2 };
}
