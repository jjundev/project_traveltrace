# Room 마이그레이션 폴백 하드닝 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 릴리스 빌드에서 forward 마이그레이션이 누락되면 저장된 여행을 조용히 삭제하지 않고 크게 실패(throw)하도록 폴백 정책을 debug/release로 분기하고, 마이그레이션 체인 누락을 CI에서 잡는 그물을 추가한다.

**Architecture:** `DatabaseModule.provideDatabase`의 무조건적 `fallbackToDestructiveMigration()`을 `applyFallbackPolicy(builder, debug)` 헬퍼로 감싸 debug에서는 파괴적 재생성(개발 편의), release(`BuildConfig.DEBUG == false`)에서는 `fallbackToDestructiveMigrationOnDowngrade()`만 허용한다. 등록해야 할 마이그레이션은 `Migrations.ALL` 한 곳에 모으고, `Migrations.ALL`이 `1..VERSION`을 빠짐없이 잇는지 검증하는 테스트로 "다음 사람이 VERSION만 올리고 마이그레이션을 빠뜨리는" 재발을 CI에서 차단한다.

**Tech Stack:** Java 17, Android View/XML, Room 2.6.1(annotationProcessor), Hilt, Robolectric 4.14.1 + JUnit 4.13.2. 배경: S8 최종 리뷰 Issue 1 (`docs/superpowers/plans/2026-07-20-s8-offline-replay-cache.md`).

## Global Constraints

- **Java + View/XML만.** Kotlin·Compose·코루틴·KSP 도입 금지.
- DI/Room은 **annotationProcessor**. Gradle은 Groovy DSL.
- `compileSdk 35 / targetSdk 35 / minSdk 33`, Java 17.
- **코드에 한국어 리터럴 금지** (테스트 어서션 메시지·주석·자바독은 예외).
- **기존 단위 테스트 회귀 0건** (기준선 203 테스트, 0 실패). 골든 스크린샷은 이 작업에서 건드리지 않는다(신규 0장).
- 전체 테스트는 `cd android && sh gradlew :app:testDebugUnitTest` 로 돌린다(`gradlew` 실행 권한이 없을 수 있어 `sh` 를 앞에 붙인다).
- 기존 파일 수정 지시의 **줄 번호는 근사치**다 — 항상 함께 적힌 코드 내용으로 위치를 찾는다.
- **마이그레이션 규약(불변):** 저장된 여행은 사용자가 되돌릴 수 없는 데이터다(재분석 = 비용). 모든 forward 마이그레이션은 **가산·인접(n→n+1)** 이어야 하며, 릴리스에서 누락 시 조용히 삭제로 처리하지 않는다.

---

## 확정된 설계 결정

**폴백 정책 = debug/release 분기** (사용자 확정).
- **debug 빌드(`BuildConfig.DEBUG == true`):** `fallbackToDestructiveMigration()` 유지. 개발 중 스키마를 자주 올리므로, 아직 마이그레이션을 안 쓴 점프는 DB 재생성으로 넘겨 반복을 빠르게 한다.
- **release 빌드(`BuildConfig.DEBUG == false`):** `fallbackToDestructiveMigrationOnDowngrade()`만. 다운그레이드만 파괴적으로 허용하고, **누락된 forward 마이그레이션은 `IllegalStateException`으로 크게 실패**시킨다 — 실사용자 데이터가 조용히 삭제되는 경로를 없앤다.

**테스트 가능성:** `BuildConfig.DEBUG`는 유닛 테스트(`testDebugUnitTest`)에서 항상 `true`라 release 분기를 직접 못 탄다. 그래서 정책 결정을 `applyFallbackPolicy(builder, boolean debug)` 헬퍼로 뽑아 `BuildConfig`와 분리하고, 테스트가 `debug=false`/`true`를 직접 넘겨 "누락 마이그레이션 → throw vs 재생성"을 검증한다. `provideDatabase`는 그 헬퍼에 `BuildConfig.DEBUG`만 꽂는 얇은 배선이다.

**범위 밖(명시적):** 실제 v2→v3 마이그레이션은 아직 필요 없다(스키마 안 바뀜) — 만들지 않는다. `room-testing`/`MigrationTestHelper` 도입 안 함(기존 `MigrationTest`처럼 raw SQLite로 충분).

---

## File Structure

**수정 (`android/app/src/main/java/com/traveltrace/app/`)**

| 경로 | 변경 |
|---|---|
| `data/db/Migrations.java` | `public static final Migration[] ALL = { MIGRATION_1_2 };` 추가 — 등록해야 할 마이그레이션의 단일 출처 |
| `di/DatabaseModule.java` | `applyFallbackPolicy(builder, debug)` 패키지-프라이빗 헬퍼 추가; `provideDatabase`가 `.addMigrations(Migrations.ALL)` + `applyFallbackPolicy(builder, BuildConfig.DEBUG)` 사용; 자바독 갱신 |

**신규 (테스트)**

| 경로 | 책임 |
|---|---|
| `android/app/src/test/java/com/traveltrace/app/di/DatabaseModuleTest.java` | ① release 정책이 누락 마이그레이션에서 throw ② debug 정책이 재생성 ③ `Migrations.ALL`이 `1..VERSION` 인접 체인을 빠짐없이 커버 |

> 테스트를 `com.traveltrace.app.di` 패키지에 두는 이유: `applyFallbackPolicy`를 `public`으로 노출하지 않고 **패키지-프라이빗**으로 유지하면서 테스트가 직접 부를 수 있게 하기 위함. 체인 가드가 읽는 `Migrations.ALL`·`TravelTraceDatabase.VERSION`은 `public`이라 패키지가 달라도 접근된다.

---

## Task 1: 폴백 정책 debug/release 분기 + 마이그레이션 체인 가드

`DatabaseModule`의 무조건적 파괴적 폴백을 정책 헬퍼로 감싸 릴리스에서 누락 마이그레이션이 데이터를 지우는 대신 실패하게 만들고, `Migrations.ALL`이 현재 DB 버전까지 인접 체인을 빠짐없이 잇는지 테스트로 못박는다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/data/db/Migrations.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/DatabaseModule.java`
- Test: `android/app/src/test/java/com/traveltrace/app/di/DatabaseModuleTest.java`

**Interfaces:**
- Consumes: `TravelTraceDatabase`(공개 `VERSION`, `NAME`, `tripDao()`, `analysisCacheDao()`), `Migrations.MIGRATION_1_2`, `com.traveltrace.app.BuildConfig.DEBUG`
- Produces:
  - `Migrations.ALL` → `Migration[]` (등록할 모든 마이그레이션)
  - `DatabaseModule.applyFallbackPolicy(RoomDatabase.Builder<TravelTraceDatabase> builder, boolean debug)` → `void` (패키지-프라이빗; `debug`면 destructive, 아니면 onDowngrade)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/di/DatabaseModuleTest.java`:

```java
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
```

- [ ] **Step 2: 테스트가 실패(컴파일 불가)하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*DatabaseModuleTest*'`
Expected: FAIL — `error: cannot find symbol: method applyFallbackPolicy(...)` 및 `error: cannot find symbol: variable ALL`

- [ ] **Step 3: `Migrations.ALL` 을 추가한다**

`android/app/src/main/java/com/traveltrace/app/data/db/Migrations.java` 의 `MIGRATION_1_2` 필드 선언(닫는 `};`) 바로 다음, 클래스 닫는 `}` 앞에 추가:

```java

    /**
     * databaseBuilder 에 등록할 모든 마이그레이션의 단일 출처. 새 마이그레이션을 만들면
     * 반드시 여기에 더한다 — 빠뜨리면 release 빌드에서 그 점프가 데이터 삭제 대신 크게
     * 실패하고(정상 방향), DatabaseModuleTest 의 체인 가드가 CI 에서 먼저 잡는다.
     */
    public static final Migration[] ALL = { MIGRATION_1_2 };
```

- [ ] **Step 4: `DatabaseModule` 을 debug/release 분기로 바꾼다**

`android/app/src/main/java/com/traveltrace/app/di/DatabaseModule.java` 전체 교체:

```java
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
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*DatabaseModuleTest*'`
Expected: PASS (3 tests)

- [ ] **Step 6: 기존 마이그레이션 테스트가 여전히 통과하는지 확인한다**

`MigrationTest`(v1→v2 가산 마이그레이션이 여행을 보존)와 `AnalysisCacheDaoTest`는 이 변경의 영향을 받지 않아야 한다.

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MigrationTest*' --tests '*DatabaseModuleTest*' --tests '*AnalysisCacheDaoTest*'`
Expected: PASS — `MigrationTest` 2개 + `DatabaseModuleTest` 3개 + `AnalysisCacheDaoTest` 4개 전부

- [ ] **Step 7: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 기준선 203개 + 신규 3개 = 206개, 실패 0

- [ ] **Step 8: 릴리스 조립이 여전히 성공하는지 확인한다**

정책 분기가 릴리스 빌드 경로(`BuildConfig.DEBUG == false`)에서도 컴파일되는지 확인한다.

Run: `cd android && sh gradlew assembleRelease`
Expected: BUILD SUCCESSFUL. (서명 설정이 없어 실패하면 대신 `sh gradlew compileReleaseJavaWithJavac` 로 컴파일만 확인한다 — 이것이 BUILD SUCCESSFUL 이면 통과로 본다.)

- [ ] **Step 9: 커밋**

```bash
cd android && git add \
  app/src/main/java/com/traveltrace/app/data/db/Migrations.java \
  app/src/main/java/com/traveltrace/app/di/DatabaseModule.java \
  app/src/test/java/com/traveltrace/app/di/DatabaseModuleTest.java
git commit -m "fix: fail loudly on a missing release migration instead of wiping saved trips

Split the destructive-migration fallback by build type: debug keeps
fallbackToDestructiveMigration() for fast schema iteration, release uses
fallbackToDestructiveMigrationOnDowngrade() so a forgotten forward migration
throws instead of silently deleting irreplaceable trips. Migrations.ALL is the
single registration point, guarded by a test that the chain covers 1..VERSION.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 수용 기준 대조표

| 요구 | 충족 지점 |
|---|---|
| release에서 누락 forward 마이그레이션이 데이터를 지우지 않고 throw | Task 1 — `applyFallbackPolicy(debug=false)` + `releasePolicyThrowsOnAMissingForwardMigrationInsteadOfWiping` |
| debug의 개발 편의(재생성) 유지 | Task 1 — `applyFallbackPolicy(debug=true)` + `debugPolicyRecreatesOnAMissingForwardMigration` |
| 미래의 "VERSION만 올리고 마이그레이션 누락" 재발 차단 | Task 1 — `Migrations.ALL` + `migrationsAllCoversEveryAdjacentVersionStepUpToCurrent` |
| 기존 v1→v2 가산 마이그레이션·캐시 회귀 0 | Task 1 Step 6·7 — `MigrationTest`/`AnalysisCacheDaoTest` + 전체 206개 |
| 자바독이 새 정책을 설명 | Task 1 Step 4 — `provideDatabase`/`applyFallbackPolicy`/`Migrations.ALL` 자바독 |
