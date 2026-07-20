package com.traveltrace.app.di;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.data.db.Migrations;
import com.traveltrace.app.data.db.TravelTraceDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;
import java.util.Set;

/**
 * 마이그레이션 폴백 정책을 검증한다. BuildConfig.DEBUG 는 유닛 테스트에서 항상 true 라
 * release 분기를 직접 못 타므로, 정책 결정을 뽑아낸 DatabaseModule.applyFallbackPolicy 에
 * boolean 을 직접 넘겨 "누락된 forward 마이그레이션 → release=throw / debug=재생성"을 본다.
 *
 * <p>기존 MigrationTest 와 같은 이유로 room-testing 대신 raw SQLite 로 v1 파일을 세운다.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseModuleTest {

    private static final String DB_NAME = "migration-policy-test.db";

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
     * 최소 v1 파일 DB 에 여행 1건. CREATE 문은 app/schemas/.../1.json 의 createSql 을 그대로
     * 옮긴 것이다(MigrationTest 와 동일). openWithPolicy 가 addMigrations 를 일부러 걸지 않으므로
     * 여기서 만든 v1 을 v2 로 여는 것이 곧 "누락된 forward 마이그레이션(1→2)" 상황이 된다.
     */
    private void createV1WithATrip() {
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

    /** addMigrations 를 일부러 걸지 않아 1→2 를 "누락된 forward 마이그레이션"으로 만든 뒤 정책만 적용한다. */
    private TravelTraceDatabase openWithPolicy(boolean debug) {
        RoomDatabase.Builder<TravelTraceDatabase> builder =
                Room.databaseBuilder(ctx, TravelTraceDatabase.class, DB_NAME)
                        .allowMainThreadQueries();
        DatabaseModule.applyFallbackPolicy(builder, debug);
        return builder.build();
    }

    @Test
    public void releasePolicyThrowsOnAMissingForwardMigrationInsteadOfWiping() {
        createV1WithATrip();
        db = openWithPolicy(/* debug= */ false);

        // Room 은 build() 가 아니라 첫 DB 접근에서 스키마를 확인한다 — 1→2 마이그레이션이
        // 없으므로 여기서 IllegalStateException 을 던져야 한다(조용한 삭제 금지).
        assertThrows(IllegalStateException.class, () -> db.tripDao().findById("t1"));
    }

    @Test
    public void debugPolicyRecreatesOnAMissingForwardMigration() {
        createV1WithATrip();
        db = openWithPolicy(/* debug= */ true);

        // debug 폴백은 마이그레이션 없는 점프를 재생성으로 넘긴다 — v1 여행은 사라지고
        // 빈 v2 로 열린다(개발 편의). 크래시 없이 사용 가능해야 한다.
        assertNull("debug 폴백은 누락 마이그레이션을 재생성으로 넘긴다", db.tripDao().findById("t1"));
        assertEquals("재생성된 DB 는 정상 사용 가능하다", 0, db.analysisCacheDao().count());
    }

    @Test
    public void migrationsAllCoversEveryAdjacentVersionStepUpToCurrent() {
        // 다음 사람이 TravelTraceDatabase.VERSION 을 올리고 Migrations.ALL 에 마이그레이션을
        // 빠뜨리면, release 폴백은 그 점프를 (조용한 삭제가 아니라) 크게 실패시킨다 — 하지만
        // 그 실패는 사용자 기기에서야 드러난다. 이 테스트가 그걸 CI 에서 미리 잡는 그물이다.
        // 이 프로젝트의 마이그레이션은 전부 인접(n→n+1) 가산이라는 규약을 전제로 한다.
        Set<Integer> covered = new HashSet<>();
        for (Migration m : Migrations.ALL) {
            assertEquals("마이그레이션은 인접 단계(n→n+1)여야 한다: "
                            + m.startVersion + "→" + m.endVersion,
                    m.startVersion + 1, m.endVersion);
            covered.add(m.startVersion);
        }
        for (int from = 1; from < TravelTraceDatabase.VERSION; from++) {
            assertTrue("v" + from + "→v" + (from + 1)
                            + " 마이그레이션이 Migrations.ALL 에 없다 — VERSION 을 올렸다면 "
                            + "마이그레이션을 만들어 ALL 에 추가하라",
                    covered.contains(from));
        }
    }
}
