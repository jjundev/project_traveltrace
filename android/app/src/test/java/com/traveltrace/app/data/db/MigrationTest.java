package com.traveltrace.app.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * v1 파일 DB 를 손으로 만들어 두고 Room 을 v2 로 열어, 가산 마이그레이션이 기존 여행을
 * 보존하면서 analysis_cache 만 더하는지 확인한다.
 *
 * <p>androidx.room:room-testing 의 MigrationTestHelper 를 쓰지 않는 이유: 이 프로젝트엔
 * androidTest 소스셋이 없고(전부 Robolectric JVM 테스트), MigrationTestHelper 는 스키마
 * JSON 을 asset 으로 읽도록 소스셋 배선을 추가로 요구한다. 여기서 검증하려는 건
 * "v1 데이터가 살아남고 새 테이블이 생기는가" 뿐이라 raw SQLite 로 충분하다.
 */
@RunWith(RobolectricTestRunner.class)
public class MigrationTest {

    private static final String DB_NAME = "migration-test.db";

    private Context ctx;
    private TravelTraceDatabase db;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
        ctx.deleteDatabase(DB_NAME);
    }

    /**
     * v1 스키마를 손으로 세운다. CREATE 문은 app/schemas/.../1.json 의 각 엔티티
     * "createSql" 값을 그대로 옮긴 것이다(${TABLE_NAME} 만 실제 테이블명으로 치환) —
     * 한 글자라도 다르면 마이그레이션 뒤 Room 의 스키마 검증이 실패하므로 손으로 짜지 말고
     * 반드시 그 파일에서 복사할 것.
     */
    private void createV1() {
        SQLiteDatabase v1 = ctx.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        v1.execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` TEXT NOT NULL, `name` TEXT NOT NULL,"
                + " `coverEmoji` TEXT, `region` TEXT, `photoCount` INTEGER NOT NULL,"
                + " `dayCount` INTEGER NOT NULL, `startDateUtc` INTEGER NOT NULL,"
                + " `endDateUtc` INTEGER NOT NULL, `timeZoneId` TEXT NOT NULL,"
                + " `createdAt` INTEGER NOT NULL, `costSpent` REAL NOT NULL, `engineMode` TEXT,"
                + " PRIMARY KEY(`id`))");
        v1.execSQL("CREATE TABLE IF NOT EXISTS `photos` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL,"
                + " `mediaStoreId` INTEGER NOT NULL, `contentHash` TEXT, `takenAtUtc` INTEGER,"
                + " `takenAtHasOffset` INTEGER NOT NULL, `displayName` TEXT, PRIMARY KEY(`id`),"
                + " FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION"
                + " ON DELETE CASCADE )");
        v1.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_tripId` ON `photos` (`tripId`)");
        v1.execSQL("CREATE TABLE IF NOT EXISTS `photo_locations` (`photoId` TEXT NOT NULL,"
                + " `lat` REAL, `lng` REAL, `source` TEXT NOT NULL, `landmarkName` TEXT,"
                + " `city` TEXT, `country` TEXT, `classification` TEXT NOT NULL,"
                + " `confidence` REAL, `detached` INTEGER NOT NULL, PRIMARY KEY(`photoId`),"
                + " FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION"
                + " ON DELETE CASCADE )");
        v1.execSQL("INSERT INTO `trips` VALUES ('t1','2024 파리 여행',NULL,NULL,2,1,"
                + "1718000000000,1718080000000,'Europe/Paris',1718090000000,0.0,NULL)");
        v1.setVersion(1);
        v1.close();
    }

    @Test
    public void migratingFromV1KeepsTripsAndAddsTheCacheTable() {
        createV1();

        db = Room.databaseBuilder(ctx, TravelTraceDatabase.class, DB_NAME)
                .addMigrations(Migrations.MIGRATION_1_2)
                .allowMainThreadQueries()
                .build();

        assertNotNull("v1 에 있던 여행은 마이그레이션 후에도 남아야 한다",
                db.tripDao().findById("t1"));
        assertEquals("새 테이블은 비어 있는 상태로 생긴다", 0, db.analysisCacheDao().count());
    }

    @Test
    public void theCacheTableIsUsableRightAfterMigration() {
        createV1();

        db = Room.databaseBuilder(ctx, TravelTraceDatabase.class, DB_NAME)
                .addMigrations(Migrations.MIGRATION_1_2)
                .allowMainThreadQueries()
                .build();

        AnalysisCacheEntity e = new AnalysisCacheEntity();
        e.mediaStoreId = 11L;
        e.contentHash = "hash-a";
        e.source = com.traveltrace.app.core.model.LocationSource.GPS;
        e.classification = com.traveltrace.app.core.model.LocationClassification.PLACED;
        e.createdAt = 1L;
        db.analysisCacheDao().upsert(e);

        assertNotNull(db.analysisCacheDao().find(11L, "hash-a"));
    }
}
