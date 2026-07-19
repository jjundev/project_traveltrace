# S1 — GPS 사진 → 시각순 경로 지도 → 저장·재조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤러리에서 고른 사진 중 EXIF GPS가 있는 것만 촬영 시각순으로 정렬해 실제 Google Maps 위에 핀·경로로 그리고, Room에 여행으로 저장해 Home에서 재조회한다.

**Architecture:** 기존 화면 4종(HOME/SELECT/ANALYZE/MAP)의 `Renderer`·레이아웃은 건드리지 않고, ViewModel의 데이터 공급원만 `ScreenFixtures` → Repository로 교체한다. 새로 생기는 층은 Room 데이터 계층, MediaStore 쿼리, EXIF 추출기, 지도 렌더 3종이다. AI·업로드·자동 재생은 이 계획의 범위 밖(S2·S3)이다.

**Tech Stack:** Java 17, Android View/XML, Fragment + Navigation Component + ViewBinding, Hilt(annotationProcessor), Room(annotationProcessor), ExecutorService, Glide, Google Maps SDK, Robolectric + Roborazzi.

## Global Constraints

- **Java + View/XML만.** Kotlin·Compose·코루틴·KSP·kotlinx.serialization 도입 금지.
- 비동기는 **`ExecutorService`** 고정. JSON은 Gson. DI/Room은 **annotationProcessor**.
- Gradle은 **Groovy DSL**, buildSrc는 Java.
- `compileSdk 35 / targetSdk 35 / minSdk 33`.
- 사용자 대면 문자열은 전부 **`res/values/strings.xml`의 한국어 리소스**. 코드에 한국어 리터럴 금지.
- **UiState 필드를 추가/변경하면 반드시 셋을 함께 확인한다** — ① `ScreenFixtures`의 생성 호출부, ② 같은 클래스의 복제 메서드(`withToggled` 류), ③ 하드코딩 문자열을 어서션하는 Renderer 테스트. (grill-review에서 이 누락으로 Blocker 4건 발생.)
- `ScreenFixtures`는 **`src/main`에 그대로 둔다.** `unknownThumbTones()` 소비처가 S6까지 살아있다. 릴리스 APK 오염은 알려진 부채.
- **기존 단위 테스트 81개는 회귀 0건.** 골든 스크린샷 14장 중 **`map_cinema.png` 1장만** 재기록하고 나머지 13장은 불변.
- API 키는 `local.properties` → `BuildConfig`/manifestPlaceholder로만 주입. 하드코딩 금지.
- 좌표 없는 사진을 **절대 (0,0)에 찍지 않는다.** 좌표가 없으면 null 로 두거나 걸러낸다 — `0d` 로 coalesce 하지 않는다.
- 기존 파일을 고치라는 지시의 **줄 번호는 근사치**다. 앞선 태스크가 같은 파일에 줄을 넣으면 밀린다 — 항상 **함께 적힌 코드 내용으로 위치를 찾는다.**

---

## File Structure

**신규 (`android/app/src/main/java/com/traveltrace/app/`)**

| 경로 | 책임 |
|---|---|
| `core/AppExecutors.java` | io 고정 풀 + main Handler. 모든 백그라운드 작업의 단일 진입점 |
| `core/model/LocationClassification.java` | `PLACED` / `NAME_ONLY` / `UNKNOWN` / `NO_TIME` |
| `domain/Callback.java` | `onResult(T)` 단일 메서드. Repository 비동기 반환 규약 |
| `domain/TripRepository.java` *(재작성)* | 여행 목록/저장/열기/삭제 |
| `domain/PhotoAnalysisRepository.java` | 사진별 분석 결과 upsert·조회 |
| `domain/model/TripSummary.java` | HOME 카드용 읽기 모델 |
| `domain/model/TripDetail.java` | MAP용 읽기 모델 (제목·타임존·스톱 목록·미상 수) |
| `domain/model/StopRow.java` | 좌표·시각·출처를 담은 정차 지점 1개 |
| `domain/model/PhotoAnalysis.java` | EXIF 추출 1건의 결과 |
| `data/db/TravelTraceDatabase.java` | `@Database` v1 |
| `data/db/TripEntity.java` · `PhotoEntity.java` · `PhotoLocationEntity.java` | Room 엔티티 3종 |
| `data/db/TripDao.java` · `PhotoDao.java` · `PhotoLocationDao.java` | DAO 3종 |
| `data/db/Converters.java` | enum ↔ String |
| `data/repo/RoomTripRepository.java` · `RoomPhotoAnalysisRepository.java` | Repository 구현 |
| `data/media/MediaStoreImageSource.java` | `_ID`·`DATE_TAKEN`·`DISPLAY_NAME` 쿼리 |
| `data/media/GalleryImage.java` | MediaStore 행 1개 |
| `data/exif/ExifExtractor.java` | `setRequireOriginal` + GPS/시각 파싱 |
| `data/exif/TimeNormalizer.java` | EXIF 시각 문자열 → UTC millis |
| `ui/photo/MediaPermissionController.java` | 허용/거부/부분허용 3경로 |
| `ui/selection/SelectionSession.java` | 선택된 `_ID` 목록 인메모리 홀더 |
| `ui/map/MapRouteRenderer.java` | 마커·폴리라인·카메라 fit |

**수정**

| 경로 | 변경 |
|---|---|
| `core/model/LocationSource.java` | 값을 `GPS`/`AI`/`NONE`으로 정렬 |
| `core/model/Trip.java`, `core/model/PhotoItem.java` | **삭제** (Room 엔티티가 대체) |
| `data/TripRepositoryStub.java` | **삭제** |
| `di/AppModule.java` | `TripRepository`·`PhotoAnalysisRepository`를 Room 구현에 바인딩 |
| `ui/home/HomeUiState.java` | `TripCard`에 `heroPhotoUri` 추가 |
| `ui/home/TripCardAdapter.java` | `heroPhotoUri` 있으면 Glide, 없으면 `heroFor(id)` 폴백 |
| `ui/home/HomeViewModel.java` | Repository 주입 |
| `ui/home/HomeFragment.java` | `navigate()`에 tripId Bundle |
| `ui/photo/PhotoSelectionUiState.java` | `Tile`에 `mediaStoreId`/`contentUri` 추가, `withToggled` 보존 |
| `ui/photo/PhotoGridAdapter.java` | `contentUri` 있으면 Glide, 없으면 `toneColor` 폴백 |
| `ui/photo/PhotoSelectionViewModel.java`, `PhotoSelectionFragment.java` | 실데이터 + 권한 |
| `ui/analysis/AnalysisViewModel.java`, `AnalysisFragment.java` | 실진행·취소·저장·tripId 전달 |
| `ui/map/MapUiState.java` | `Stop`에 `lat`/`lng` 추가 |
| `ui/map/MapRenderer.java` | `renderCinema()`에서 `cityLabel` 파라미터 제거 |
| `ui/map/MapReplayViewModel.java` | `SavedStateHandle` + Repository, `city()` 삭제 |
| `ui/map/MapReplayFragment.java` | `MapRouteRenderer` 연결 |
| `ui/preview/ScreenFixtures.java` | `Stop` 6곳에 좌표, `TripCard` 2곳에 null hero, `cityLabel()` 삭제 |
| `res/navigation/nav_graph.xml` | `mapReplayFragment`에 `tripId` argument |
| `res/values/strings.xml` | 신규 문자열 |
| `AndroidManifest.xml` | `READ_MEDIA_VISUAL_USER_SELECTED` |
| `gradle/libs.versions.toml`, `app/build.gradle` | Glide, `room.schemaLocation` |
| `src/test/.../CinemaOverlayRendererTest.java`, `CinemaOverlayScreenshotTest.java`, `HomeRendererTest.java` | 시그니처·어서션 갱신 |

---

## Task 1: AppExecutors — 백그라운드 실행 단일 진입점

이후 모든 Repository·EXIF·MediaStore 작업이 이 클래스를 경유한다. 메인스레드 DB/디스크 접근을 구조적으로 막는 것이 목적이다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/core/AppExecutors.java`
- Create: `android/app/src/main/java/com/traveltrace/app/domain/Callback.java`
- Test: `android/app/src/test/java/com/traveltrace/app/core/AppExecutorsTest.java`

**Interfaces:**
- Consumes: 없음 (최하위)
- Produces: `AppExecutors.io()` → `ExecutorService`, `AppExecutors.mainThread()` → `Executor`, `AppExecutors.shutdown()` → `void`; `Callback<T>.onResult(T value)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/core/AppExecutorsTest.java`:

```java
package com.traveltrace.app.core;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AppExecutorsTest {

    @Test
    public void ioRunsOffTheMainThread() throws Exception {
        AppExecutors executors = new AppExecutors();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executors.io().execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertTrue("io 작업이 2초 안에 끝나야 한다", done.await(2, TimeUnit.SECONDS));
        assertNotEquals("io 는 메인스레드에서 돌면 안 된다",
                Thread.currentThread(), ran.get());
        executors.shutdown();
    }

    @Test
    public void mainThreadPostsToTheMainLooper() {
        AppExecutors executors = new AppExecutors();
        AtomicReference<Boolean> ranOnMain = new AtomicReference<>(null);

        executors.mainThread().execute(() ->
                ranOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()));

        // Robolectric 은 메인 루퍼를 자동으로 돌리지 않는다 — 명시적으로 비운다.
        ShadowLooper.idleMainLooper();

        assertTrue("mainThread 는 메인 루퍼에서 실행되어야 한다", Boolean.TRUE.equals(ranOnMain.get()));
        executors.shutdown();
    }

    @Test
    public void ioPoolIsAtLeastFourThreads() {
        assertTrue("동시 EXIF 읽기를 위해 최소 4스레드", AppExecutors.ioPoolSize() >= 4);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AppExecutorsTest*'`
Expected: FAIL — `error: cannot find symbol: class AppExecutors`

- [ ] **Step 3: `Callback` 을 만든다**

`android/app/src/main/java/com/traveltrace/app/domain/Callback.java`:

```java
package com.traveltrace.app.domain;

/**
 * Repository 비동기 반환 규약. 구현은 결과를 항상 메인스레드에서 전달한다
 * (AppExecutors.mainThread() 경유) — 호출부가 스레드를 신경 쓰지 않게 하기 위함.
 */
public interface Callback<T> {
    void onResult(T value);
}
```

- [ ] **Step 4: `AppExecutors` 를 만든다**

`android/app/src/main/java/com/traveltrace/app/core/AppExecutors.java`:

```java
package com.traveltrace.app.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 백그라운드 실행의 단일 진입점. PRD §6: 코루틴 없이 ExecutorService 고정.
 *
 * <p>io 풀 크기는 로컬 I/O(EXIF 읽기·MediaStore 커서·Room) 기준으로 정한다.
 * plan/10 의 "동시 4건"은 rate-limit 하의 <em>네트워크</em> 상한이라 이 값과 무관하다.
 */
@Singleton
public class AppExecutors {

    private static final int MIN_IO_THREADS = 4;

    private final ExecutorService io;
    private final Executor mainThread;

    @Inject
    public AppExecutors() {
        this.io = Executors.newFixedThreadPool(ioPoolSize());
        Handler handler = new Handler(Looper.getMainLooper());
        this.mainThread = handler::post;
    }

    /** 로컬 I/O는 코어 수를 넘겨도 이득이 없고, 4 미만이면 100장 배치가 느려진다. */
    public static int ioPoolSize() {
        return Math.max(MIN_IO_THREADS, Runtime.getRuntime().availableProcessors());
    }

    public ExecutorService io() {
        return io;
    }

    public Executor mainThread() {
        return mainThread;
    }

    public void shutdown() {
        io.shutdownNow();
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AppExecutorsTest*'`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/core/AppExecutors.java \
  app/src/main/java/com/traveltrace/app/domain/Callback.java \
  app/src/test/java/com/traveltrace/app/core/AppExecutorsTest.java
git commit -m "feat: add AppExecutors as the single background-execution entry point"
```

---

## Task 2: Room 스키마 — 엔티티 3종 · DAO · 데이터베이스

`plan/04-data-layer.md`의 스키마 초안을 v1으로 구현한다. `AnalysisCache`는 AI 호출을 막는 장치라 S1엔 무의미하므로 만들지 않는다(S4/S8). 미출시 상태이므로 개발 중에는 파괴적 마이그레이션을 허용하되, `exportSchema=true`로 변경 이력은 커밋한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/core/model/LocationClassification.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/db/{TripEntity,PhotoEntity,PhotoLocationEntity,Converters,TripDao,PhotoDao,PhotoLocationDao,TravelTraceDatabase}.java`
- Create: `android/app/src/main/java/com/traveltrace/app/domain/model/{TripSummary,StopRow}.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/core/model/LocationSource.java`
- Modify: `android/app/build.gradle` (defaultConfig 안, `room.schemaLocation`)
- Test: `android/app/src/test/java/com/traveltrace/app/data/db/TravelTraceDatabaseTest.java`

**Interfaces:**
- Consumes: Task 1 없음 (독립)
- Produces: `TripDao.insert(TripEntity)`, `TripDao.listSummaries()` → `List<TripSummary>`, `TripDao.findById(String)` → `TripEntity`, `TripDao.deleteById(String)`; `PhotoDao.insertAll(List<PhotoEntity>)`, `PhotoDao.heroMediaStoreId(String)` → `Long`; `PhotoLocationDao.insertAll(List<PhotoLocationEntity>)`, `PhotoLocationDao.stopsFor(String)` → `List<StopRow>`, `PhotoLocationDao.countByClassification(String, LocationClassification)` → `int`; `TravelTraceDatabase.tripDao()/photoDao()/photoLocationDao()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/db/TravelTraceDatabaseTest.java`:

```java
package com.traveltrace.app.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.domain.model.StopRow;
import com.traveltrace.app.domain.model.TripSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TravelTraceDatabaseTest {

    private TravelTraceDatabase db;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static TripEntity trip(String id) {
        TripEntity t = new TripEntity();
        t.id = id;
        t.name = "2024 6월 여행";
        t.photoCount = 2;
        t.dayCount = 1;
        t.startDateUtc = 1_718_000_000_000L;
        t.endDateUtc = 1_718_080_000_000L;
        t.timeZoneId = "Asia/Seoul";
        t.createdAt = 1_718_090_000_000L;
        return t;
    }

    private static PhotoEntity photo(String id, String tripId, long mediaStoreId,
                                     Long takenAtUtc, String displayName) {
        PhotoEntity p = new PhotoEntity();
        p.id = id;
        p.tripId = tripId;
        p.mediaStoreId = mediaStoreId;
        p.takenAtUtc = takenAtUtc;
        p.takenAtHasOffset = true;
        p.displayName = displayName;
        return p;
    }

    private static PhotoLocationEntity placed(String photoId, double lat, double lng) {
        PhotoLocationEntity l = new PhotoLocationEntity();
        l.photoId = photoId;
        l.lat = lat;
        l.lng = lng;
        l.source = LocationSource.GPS;
        l.classification = LocationClassification.PLACED;
        l.detached = false;
        return l;
    }

    @Test
    public void tripRoundTripsAndSummaryCarriesTheHeroPhoto() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Arrays.asList(
                photo("p2", "t1", 222L, 2_000L, "b.jpg"),
                photo("p1", "t1", 111L, 1_000L, "a.jpg")));

        List<TripSummary> summaries = db.tripDao().listSummaries();

        assertEquals(1, summaries.size());
        TripSummary s = summaries.get(0);
        assertEquals("t1", s.id);
        assertEquals("2024 6월 여행", s.name);
        assertEquals(2, s.photoCount);
        assertEquals("hero 는 가장 이른 촬영 시각의 사진이어야 한다",
                Long.valueOf(111L), s.heroMediaStoreId);
    }

    @Test
    public void stopsAreOrderedByTakenAtAndExcludeNonPlaced() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Arrays.asList(
                photo("late", "t1", 2L, 2_000L, "late.jpg"),
                photo("early", "t1", 1L, 1_000L, "early.jpg"),
                photo("nogps", "t1", 3L, 1_500L, "nogps.jpg")));

        PhotoLocationEntity unknown = new PhotoLocationEntity();
        unknown.photoId = "nogps";
        unknown.source = LocationSource.NONE;
        unknown.classification = LocationClassification.UNKNOWN;
        unknown.detached = false;

        db.photoLocationDao().insertAll(Arrays.asList(
                placed("late", 48.86, 2.35),
                placed("early", 48.85, 2.29),
                unknown));

        List<StopRow> stops = db.photoLocationDao().stopsFor("t1");

        assertEquals("PLACED 만 경로에 들어간다", 2, stops.size());
        assertEquals("early", stops.get(0).photoId);
        assertEquals("late", stops.get(1).photoId);
        assertNotNull(stops.get(0).lat);
    }

    @Test
    public void unknownCountIsQueryable() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Collections.singletonList(
                photo("nogps", "t1", 3L, null, "nogps.jpg")));

        PhotoLocationEntity unknown = new PhotoLocationEntity();
        unknown.photoId = "nogps";
        unknown.source = LocationSource.NONE;
        unknown.classification = LocationClassification.UNKNOWN;
        unknown.detached = false;
        db.photoLocationDao().insertAll(Collections.singletonList(unknown));

        assertEquals(1, db.photoLocationDao()
                .countByClassification("t1", LocationClassification.UNKNOWN));
    }

    @Test
    public void deletingATripCascadesToPhotosAndLocations() {
        db.tripDao().insert(trip("t1"));
        db.photoDao().insertAll(Collections.singletonList(
                photo("p1", "t1", 1L, 1_000L, "a.jpg")));
        db.photoLocationDao().insertAll(Collections.singletonList(placed("p1", 48.85, 2.29)));

        db.tripDao().deleteById("t1");

        assertNull(db.tripDao().findById("t1"));
        assertEquals(0, db.photoLocationDao().stopsFor("t1").size());
        assertEquals("여행이 지워지면 사진 행도 남지 않는다",
                0, db.photoDao().countForTrip("t1"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*TravelTraceDatabaseTest*'`
Expected: FAIL — `error: cannot find symbol: class TravelTraceDatabase`

- [ ] **Step 3: enum 2종을 정리한다**

`android/app/src/main/java/com/traveltrace/app/core/model/LocationSource.java` (전체 교체):

```java
package com.traveltrace.app.core.model;

/** 위치 좌표의 출처 (PRD §4.2 우선순위 규칙). */
public enum LocationSource {
    /** EXIF GPS 에서 직접 읽은 좌표. 가장 정확하고 AI 호출이 없다. */
    GPS,
    /** AI 이름 추론 + 지오코딩으로 얻은 근사 위치. S3 에서 생긴다. */
    AI,
    /** 좌표를 얻지 못함. */
    NONE
}
```

`android/app/src/main/java/com/traveltrace/app/core/model/LocationClassification.java` (신규):

```java
package com.traveltrace.app.core.model;

/** 사진 1장의 위치 판정 결과 (PRD §4.6 분류 3종 + 시각 없음). */
public enum LocationClassification {
    /** 좌표·시각이 모두 있어 경로에 그려진다. */
    PLACED,
    /** 이름은 알아냈지만 좌표화 실패. 지도 제외. S3 에서 생긴다. */
    NAME_ONLY,
    /** GPS·AI 모두 실패. 위치 미상 그룹. */
    UNKNOWN,
    /** 좌표는 있으나 촬영 시각이 없어 경로 순서에서 제외된다. */
    NO_TIME
}
```

- [ ] **Step 4: 엔티티 3종과 Converters 를 만든다**

`data/db/TripEntity.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/** plan/04-data-layer.md 의 Trip 스키마. costSpent·engineMode 는 S4/S5 가 채운다. */
@Entity(tableName = "trips")
public class TripEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String name;

    @Nullable
    public String coverEmoji;

    @Nullable
    public String region;

    public int photoCount;

    public int dayCount;

    public long startDateUtc;

    public long endDateUtc;

    /** 여행 기준 타임존 (PRD §4.2, v1 은 여행당 1개). */
    public String timeZoneId;

    public long createdAt;

    /** S4 비용 상한 집계용. S1 은 0 으로 둔다. */
    public double costSpent;

    /** S5 엔진 모드(dual/single). S1 은 null. */
    @Nullable
    public String engineMode;
}
```

`data/db/PhotoEntity.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * plan/04-data-layer.md 의 Photo 스키마.
 *
 * <p>contentHash 는 S1 에서 채우지 않는다 — 해시는 S3 업로드 파이프라인이 사진 스트림을
 * 읽는 김에 1회 계산한다(중복 I/O 회피, plan/04). 여기선 컬럼만 미리 만든다.
 */
@Entity(
        tableName = "photos",
        foreignKeys = @ForeignKey(
                entity = TripEntity.class,
                parentColumns = "id",
                childColumns = "tripId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("tripId")})
public class PhotoEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String tripId;

    /** 기기 내에서 안정적인 캐시 키의 절반 (PRD §4.1 MediaStore 채택 사유). */
    public long mediaStoreId;

    @Nullable
    public String contentHash;

    /** UTC millis. 촬영 시각을 못 읽었으면 null → classification=NO_TIME. */
    @Nullable
    public Long takenAtUtc;

    /** EXIF 에 OffsetTimeOriginal 이 있었는지. false 면 기기 타임존으로 폴백한 값이다. */
    public boolean takenAtHasOffset;

    public String displayName;
}
```

`data/db/PhotoLocationEntity.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** plan/04-data-layer.md 의 PhotoLocation 스키마. photoId 가 PK 이자 FK. */
@Entity(
        tableName = "photo_locations",
        foreignKeys = @ForeignKey(
                entity = PhotoEntity.class,
                parentColumns = "id",
                childColumns = "photoId",
                onDelete = ForeignKey.CASCADE))
public class PhotoLocationEntity {

    @PrimaryKey
    @NonNull
    public String photoId = "";

    /** 좌표가 없으면 null. 절대 0.0 으로 채우지 않는다 — (0,0) 핀 방지. */
    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    public LocationSource source;

    @Nullable
    public String landmarkName;

    @Nullable
    public String city;

    @Nullable
    public String country;

    public LocationClassification classification;

    @Nullable
    public Double confidence;

    /** 리플레이에서 위치 미상으로 뺀 상태 (S6). S1 은 항상 false. */
    public boolean detached;
}
```

`data/db/Converters.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** enum ↔ String. 이름으로 저장해야 enum 순서를 바꿔도 기존 행이 안 깨진다. */
public class Converters {

    @TypeConverter
    @Nullable
    public static String fromSource(@Nullable LocationSource source) {
        return source == null ? null : source.name();
    }

    @TypeConverter
    @Nullable
    public static LocationSource toSource(@Nullable String name) {
        return name == null ? null : LocationSource.valueOf(name);
    }

    @TypeConverter
    @Nullable
    public static String fromClassification(@Nullable LocationClassification c) {
        return c == null ? null : c.name();
    }

    @TypeConverter
    @Nullable
    public static LocationClassification toClassification(@Nullable String name) {
        return name == null ? null : LocationClassification.valueOf(name);
    }
}
```

- [ ] **Step 5: 읽기 모델 2종을 만든다**

`domain/model/TripSummary.java`:

```java
package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

/** HOME 카드 1장을 채우는 읽기 모델. Room @Query 가 직접 채운다. */
public class TripSummary {
    public String id;
    public String name;
    public int photoCount;
    public int dayCount;
    public long startDateUtc;
    public String timeZoneId;

    /** 카드 hero 로 쓸 첫 사진. 사진이 없으면 null. */
    @Nullable
    public Long heroMediaStoreId;
}
```

`domain/model/StopRow.java`:

```java
package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationSource;

/** 경로 위 정차 지점 1개. PLACED 인 사진만 이 형태로 나온다. */
public class StopRow {
    public String photoId;
    public long mediaStoreId;

    /** PLACED 는 항상 non-null 이지만 Room 컬럼이 nullable 이라 박싱 타입으로 받는다. */
    @Nullable
    public Long takenAtUtc;

    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    public LocationSource source;

    @Nullable
    public String landmarkName;

    public String displayName;
}
```

- [ ] **Step 6: DAO 3종과 Database 를 만든다**

`data/db/TripDao.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

@Dao
public interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TripEntity trip);

    /**
     * HOME 목록. hero 는 가장 이른 촬영 시각의 사진 — 시각이 없는 사진은 뒤로 밀린다
     * (SQLite 는 NULL 을 가장 작은 값으로 정렬하므로 IS NULL 을 먼저 태운다).
     */
    @Query("SELECT t.id AS id, t.name AS name, t.photoCount AS photoCount, "
            + "t.dayCount AS dayCount, t.startDateUtc AS startDateUtc, "
            + "t.timeZoneId AS timeZoneId, "
            + "(SELECT p.mediaStoreId FROM photos p WHERE p.tripId = t.id "
            + " ORDER BY (p.takenAtUtc IS NULL), p.takenAtUtc ASC, p.displayName ASC LIMIT 1) "
            + "AS heroMediaStoreId "
            + "FROM trips t ORDER BY t.createdAt DESC")
    List<TripSummary> listSummaries();

    @Nullable
    @Query("SELECT * FROM trips WHERE id = :id")
    TripEntity findById(String id);

    @Query("DELETE FROM trips WHERE id = :id")
    void deleteById(String id);
}
```

`data/db/PhotoDao.java`:

```java
package com.traveltrace.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<PhotoEntity> photos);

    @Query("SELECT COUNT(*) FROM photos WHERE tripId = :tripId")
    int countForTrip(String tripId);
}
```

`data/db/PhotoLocationDao.java`:

```java
package com.traveltrace.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.domain.model.StopRow;

import java.util.List;

@Dao
public interface PhotoLocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<PhotoLocationEntity> locations);

    /**
     * 경로에 그릴 정차 지점. PLACED 이고 빼지 않은 것만, 촬영 시각 오름차순.
     * 동일 초(연사)는 displayName 으로 안정 tiebreak (plan/06).
     */
    @Query("SELECT p.id AS photoId, p.mediaStoreId AS mediaStoreId, "
            + "p.takenAtUtc AS takenAtUtc, p.displayName AS displayName, "
            + "l.lat AS lat, l.lng AS lng, l.source AS source, "
            + "l.landmarkName AS landmarkName "
            + "FROM photos p INNER JOIN photo_locations l ON l.photoId = p.id "
            + "WHERE p.tripId = :tripId AND l.classification = 'PLACED' AND l.detached = 0 "
            + "ORDER BY p.takenAtUtc ASC, p.displayName ASC")
    List<StopRow> stopsFor(String tripId);

    @Query("SELECT COUNT(*) FROM photos p INNER JOIN photo_locations l ON l.photoId = p.id "
            + "WHERE p.tripId = :tripId AND l.classification = :classification")
    int countByClassification(String tripId, LocationClassification classification);
}
```

`data/db/TravelTraceDatabase.java`:

```java
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
```

- [ ] **Step 7: `room.schemaLocation` 을 빌드에 연결한다**

`android/app/build.gradle` 의 `defaultConfig { ... }` 블록 안, `versionName '0.1.0'` 줄 바로 다음에 추가:

```groovy
        // exportSchema=true 의 산출물 경로. 커밋해서 스키마 변경 이력을 남긴다.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += ["room.schemaLocation": "$projectDir/schemas".toString()]
            }
        }
```

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*TravelTraceDatabaseTest*'`
Expected: PASS (4 tests)

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 기존 81개 + 신규 7개, 실패 0

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/core/model/ \
  app/src/main/java/com/traveltrace/app/data/db/ \
  app/src/main/java/com/traveltrace/app/domain/model/ \
  app/src/test/java/com/traveltrace/app/data/db/ \
  app/build.gradle app/schemas/
git commit -m "feat: add Room schema v1 (trips, photos, photo_locations)"
```

---

## Task 3: Repository 계층 — 도메인 인터페이스 재설계 · Room 구현 · DI 교체

현 `TripRepository`는 `save/load/list`가 전부 동기 시그니처라 메인스레드 DB 접근을 유도한다. 콜백 반환으로 바꾸고 Room 구현을 붙인 뒤, 죽은 POJO(`Trip`/`PhotoItem`)와 `TripRepositoryStub`을 지운다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/domain/model/{TripDetail,PhotoAnalysis}.java`
- Create: `android/app/src/main/java/com/traveltrace/app/domain/PhotoAnalysisRepository.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/repo/{RoomTripRepository,RoomPhotoAnalysisRepository}.java`
- Create: `android/app/src/main/java/com/traveltrace/app/di/DatabaseModule.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/domain/TripRepository.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/AppModule.java`
- Delete: `android/app/src/main/java/com/traveltrace/app/core/model/Trip.java`, `core/model/PhotoItem.java`, `data/TripRepositoryStub.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/repo/RoomTripRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 `AppExecutors`, `Callback<T>`; Task 2 `TravelTraceDatabase`, `TripSummary`, `StopRow`, `LocationSource`, `LocationClassification`
- Produces:
  - `TripRepository.list(Callback<List<TripSummary>>)`, `TripRepository.open(String tripId, Callback<TripDetail>)`, `TripRepository.delete(String tripId, Callback<Void>)`
  - `PhotoAnalysisRepository.saveTrip(String name, String timeZoneId, List<PhotoAnalysis> results, Callback<String> onTripId)`
  - `PhotoAnalysis` 공개 필드: `mediaStoreId`, `displayName`, `takenAtUtc`(`Long`), `takenAtHasOffset`, `lat`(`Double`), `lng`(`Double`), `source`, `classification`
  - `TripDetail` 공개 필드: `id`, `name`, `timeZoneId`, `stops`(`List<StopRow>`), `unknownCount`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/repo/RoomTripRepositoryTest.java`:

```java
package com.traveltrace.app.data.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.model.PhotoAnalysis;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class RoomTripRepositoryTest {

    private TravelTraceDatabase db;
    private AppExecutors executors;
    private RoomTripRepository tripRepo;
    private RoomPhotoAnalysisRepository analysisRepo;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        executors = new AppExecutors();
        tripRepo = new RoomTripRepository(db, executors);
        analysisRepo = new RoomPhotoAnalysisRepository(db, executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    private static PhotoAnalysis placed(long id, String name, long takenAt,
                                        double lat, double lng) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = takenAt;
        a.takenAtHasOffset = true;
        a.lat = lat;
        a.lng = lng;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private static PhotoAnalysis unknown(long id, String name) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = null;
        a.takenAtHasOffset = false;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    /** 콜백이 메인 루퍼로 오므로 테스트에서 루퍼를 비워 결과를 받는다. */
    private static <T> T await(java.util.function.Consumer<com.traveltrace.app.domain.Callback<T>> call) {
        AtomicReference<T> box = new AtomicReference<>();
        call.accept(box::set);
        ShadowLooper.idleMainLooper();
        return box.get();
    }

    @Test
    public void savedTripIsListedAndReopenableWithoutReanalysis() {
        List<PhotoAnalysis> results = Arrays.asList(
                placed(2L, "b.jpg", 2_000L, 48.8584, 2.2945),
                placed(1L, "a.jpg", 1_000L, 48.8606, 2.3376),
                unknown(3L, "c.jpg"));

        String tripId = await(cb ->
                analysisRepo.saveTrip("2024 6월 여행", "Europe/Paris", results, cb));

        assertNotNull("저장은 tripId 를 돌려줘야 한다", tripId);

        List<TripSummary> list = await(cb -> tripRepo.list(cb));
        assertEquals(1, list.size());
        assertEquals("2024 6월 여행", list.get(0).name);
        assertEquals("사진 3장이 전부 집계되어야 한다", 3, list.get(0).photoCount);
        assertEquals("hero 는 가장 이른 사진", Long.valueOf(1L), list.get(0).heroMediaStoreId);

        TripDetail detail = await(cb -> tripRepo.open(tripId, cb));
        assertNotNull(detail);
        assertEquals("Europe/Paris", detail.timeZoneId);
        assertEquals("PLACED 2장만 경로에 오른다", 2, detail.stops.size());
        assertEquals("시각순 정렬", 1L, detail.stops.get(0).mediaStoreId);
        assertEquals("위치 미상 1장", 1, detail.unknownCount);
    }

    @Test
    public void openingAnUnknownTripIdYieldsNull() {
        TripDetail detail = await(cb -> tripRepo.open("nope", cb));
        assertNull(detail);
    }

    @Test
    public void deleteRemovesTheTripFromTheList() {
        String tripId = await(cb -> analysisRepo.saveTrip(
                "지울 여행", "Asia/Seoul",
                new ArrayList<>(Arrays.asList(placed(1L, "a.jpg", 1_000L, 37.5, 127.0))), cb));

        await(cb -> tripRepo.delete(tripId, cb));

        assertTrue(await(cb -> tripRepo.list(cb)).isEmpty());
    }

    @Test
    public void tripWithNoPlacedPhotosStillSavesAndOpensWithEmptyStops() {
        String tripId = await(cb -> analysisRepo.saveTrip(
                "전부 미상", "Asia/Seoul",
                new ArrayList<>(Arrays.asList(unknown(1L, "a.jpg"))), cb));

        TripDetail detail = await(cb -> tripRepo.open(tripId, cb));
        assertNotNull(detail);
        assertTrue("좌표 없는 사진은 스톱이 되지 않는다", detail.stops.isEmpty());
        assertEquals(1, detail.unknownCount);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*RoomTripRepositoryTest*'`
Expected: FAIL — `error: cannot find symbol: class RoomTripRepository`

- [ ] **Step 3: 도메인 모델 2종을 만든다**

`domain/model/PhotoAnalysis.java`:

```java
package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** 사진 1장의 EXIF 추출 결과. 저장 전 단계의 운반 객체. */
public class PhotoAnalysis {
    public long mediaStoreId;
    public String displayName;

    /** 촬영 시각(UTC millis). 못 읽었으면 null. */
    @Nullable
    public Long takenAtUtc;

    public boolean takenAtHasOffset;

    /** 좌표가 없으면 null 로 둔다 — 0.0 으로 채우면 (0,0) 핀이 생긴다. */
    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    public LocationSource source;
    public LocationClassification classification;
}
```

`domain/model/TripDetail.java`:

```java
package com.traveltrace.app.domain.model;

import java.util.Collections;
import java.util.List;

/** MAP 화면이 필요한 저장 여행 1건. AI·EXIF 재실행 없이 이것만으로 재생한다. */
public class TripDetail {
    public String id;
    public String name;
    public String timeZoneId;
    public List<StopRow> stops = Collections.emptyList();
    public int unknownCount;
}
```

- [ ] **Step 4: 도메인 인터페이스를 재작성한다**

`domain/TripRepository.java` (전체 교체):

```java
package com.traveltrace.app.domain;

import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

/**
 * 여행 저장/조회 (PRD §4.7). 모든 메서드는 백그라운드에서 실행되고
 * 결과를 <em>메인스레드</em>에서 콜백으로 돌려준다 — 호출부는 스레드를 신경 쓰지 않는다.
 */
public interface TripRepository {

    /** HOME 목록. 최신 생성순. */
    void list(Callback<List<TripSummary>> callback);

    /** 저장 여행 열기. 없으면 null 을 돌려준다. */
    void open(String tripId, Callback<TripDetail> callback);

    /** 여행과 연관 사진/위치를 지운다(cascade). 완료 시 null 로 콜백. */
    void delete(String tripId, Callback<Void> callback);
}
```

`domain/PhotoAnalysisRepository.java` (신규):

```java
package com.traveltrace.app.domain;

import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.List;

/** 분석 결과를 여행 1건으로 확정 저장한다. */
public interface PhotoAnalysisRepository {

    /**
     * 결과 전체를 한 트랜잭션으로 저장하고 새 tripId 를 돌려준다.
     * 기간·장수·일수는 results 에서 파생되므로 호출부가 계산하지 않는다.
     */
    void saveTrip(String name, String timeZoneId, List<PhotoAnalysis> results,
                  Callback<String> callback);
}
```

- [ ] **Step 5: Room 구현 2종을 만든다**

`data/repo/RoomTripRepository.java`:

```java
package com.traveltrace.app.data.repo;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.db.TripEntity;
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RoomTripRepository implements TripRepository {

    private final TravelTraceDatabase db;
    private final AppExecutors executors;

    @Inject
    public RoomTripRepository(TravelTraceDatabase db, AppExecutors executors) {
        this.db = db;
        this.executors = executors;
    }

    @Override
    public void list(Callback<List<TripSummary>> callback) {
        executors.io().execute(() -> {
            List<TripSummary> summaries = db.tripDao().listSummaries();
            executors.mainThread().execute(() -> callback.onResult(summaries));
        });
    }

    @Override
    public void open(String tripId, Callback<TripDetail> callback) {
        executors.io().execute(() -> {
            TripEntity entity = db.tripDao().findById(tripId);
            TripDetail detail;
            if (entity == null) {
                detail = null;
            } else {
                detail = new TripDetail();
                detail.id = entity.id;
                detail.name = entity.name;
                detail.timeZoneId = entity.timeZoneId;
                detail.stops = db.photoLocationDao().stopsFor(tripId);
                detail.unknownCount = db.photoLocationDao()
                        .countByClassification(tripId, LocationClassification.UNKNOWN);
            }
            TripDetail result = detail;
            executors.mainThread().execute(() -> callback.onResult(result));
        });
    }

    @Override
    public void delete(String tripId, Callback<Void> callback) {
        executors.io().execute(() -> {
            db.tripDao().deleteById(tripId);
            executors.mainThread().execute(() -> callback.onResult(null));
        });
    }
}
```

`data/repo/RoomPhotoAnalysisRepository.java`:

```java
package com.traveltrace.app.data.repo;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.PhotoEntity;
import com.traveltrace.app.data.db.PhotoLocationEntity;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.db.TripEntity;
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RoomPhotoAnalysisRepository implements PhotoAnalysisRepository {

    private final TravelTraceDatabase db;
    private final AppExecutors executors;

    @Inject
    public RoomPhotoAnalysisRepository(TravelTraceDatabase db, AppExecutors executors) {
        this.db = db;
        this.executors = executors;
    }

    @Override
    public void saveTrip(String name, String timeZoneId, List<PhotoAnalysis> results,
                         Callback<String> callback) {
        executors.io().execute(() -> {
            String tripId = UUID.randomUUID().toString();

            List<PhotoEntity> photos = new ArrayList<>();
            List<PhotoLocationEntity> locations = new ArrayList<>();
            long earliest = Long.MAX_VALUE;
            long latest = Long.MIN_VALUE;

            for (PhotoAnalysis a : results) {
                String photoId = tripId + ":" + a.mediaStoreId;

                PhotoEntity p = new PhotoEntity();
                p.id = photoId;
                p.tripId = tripId;
                p.mediaStoreId = a.mediaStoreId;
                p.takenAtUtc = a.takenAtUtc;
                p.takenAtHasOffset = a.takenAtHasOffset;
                p.displayName = a.displayName;
                photos.add(p);

                PhotoLocationEntity l = new PhotoLocationEntity();
                l.photoId = photoId;
                l.lat = a.lat;
                l.lng = a.lng;
                l.source = a.source;
                l.classification = a.classification;
                l.detached = false;
                locations.add(l);

                if (a.takenAtUtc != null) {
                    earliest = Math.min(earliest, a.takenAtUtc);
                    latest = Math.max(latest, a.takenAtUtc);
                }
            }

            long now = System.currentTimeMillis();
            boolean hasTimes = earliest != Long.MAX_VALUE;

            TripEntity trip = new TripEntity();
            trip.id = tripId;
            trip.name = name;
            trip.photoCount = results.size();
            trip.startDateUtc = hasTimes ? earliest : now;
            trip.endDateUtc = hasTimes ? latest : now;
            trip.dayCount = dayCount(trip.startDateUtc, trip.endDateUtc);
            trip.timeZoneId = timeZoneId;
            trip.createdAt = now;
            trip.costSpent = 0d;

            db.runInTransaction(() -> {
                db.tripDao().insert(trip);
                db.photoDao().insertAll(photos);
                db.photoLocationDao().insertAll(locations);
            });

            executors.mainThread().execute(() -> callback.onResult(tripId));
        });
    }

    /** 시작·종료가 같은 날이어도 1일. 경계 정밀도는 여행 타임존 도입(S4) 전까지 근사로 둔다. */
    private static int dayCount(long startUtc, long endUtc) {
        long span = Math.max(0L, endUtc - startUtc);
        return (int) (TimeUnit.MILLISECONDS.toDays(span) + 1);
    }
}
```

- [ ] **Step 6: DI 를 교체하고 죽은 코드를 지운다**

`di/DatabaseModule.java` (신규):

```java
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
```

`di/AppModule.java` (전체 교체):

```java
package com.traveltrace.app.di;

import com.traveltrace.app.data.GeocoderStub;
import com.traveltrace.app.data.VisionProviderStub;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * VisionProvider·Geocoder 는 S3(AI 인식)까지 스텁으로 남는다 — S1 은 EXIF GPS 만 쓴다.
 * 여행 저장/조회는 여기서 Room 구현으로 교체됐다.
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class AppModule {

    @Binds
    @Singleton
    public abstract VisionProvider bindVisionProvider(VisionProviderStub impl);

    @Binds
    @Singleton
    public abstract Geocoder bindGeocoder(GeocoderStub impl);

    @Binds
    @Singleton
    public abstract TripRepository bindTripRepository(RoomTripRepository impl);

    @Binds
    @Singleton
    public abstract PhotoAnalysisRepository bindPhotoAnalysisRepository(
            RoomPhotoAnalysisRepository impl);
}
```

죽은 코드를 지운다:

```bash
cd android && rm app/src/main/java/com/traveltrace/app/core/model/Trip.java \
  app/src/main/java/com/traveltrace/app/core/model/PhotoItem.java \
  app/src/main/java/com/traveltrace/app/data/TripRepositoryStub.java
```

> `GeoPoint`·`GeocodeQuery`·`RecognitionResult` 는 **지우지 않는다** — `Geocoder`/`VisionProvider` 인터페이스가 S3 까지 이들을 참조한다.

- [ ] **Step 7: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*RoomTripRepositoryTest*'`
Expected: PASS (4 tests)

- [ ] **Step 8: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. (`ScreenFixturesTest` 등 기존 81개가 그대로 통과해야 한다.)

- [ ] **Step 9: 커밋**

```bash
cd android && git add -A app/src/main/java/com/traveltrace/app/domain/ \
  app/src/main/java/com/traveltrace/app/data/ \
  app/src/main/java/com/traveltrace/app/core/model/ \
  app/src/main/java/com/traveltrace/app/di/ \
  app/src/test/java/com/traveltrace/app/data/repo/
git commit -m "feat: replace the TripRepository stub with a Room-backed implementation"
```

---

## Task 4: Glide + MediaStoreImageSource — 갤러리 목록 읽기

`MediaStore.Images` 에서 `_ID`·`DATE_TAKEN`·`DISPLAY_NAME` 만 뽑는다. 커서에서 나오는 건 long/문자열뿐이라 수천 장도 수 MB 미만이므로 Paging3 없이 1회 전량 쿼리한다. 비싼 건 비트맵이고 그건 Glide 가 바인드 시점에 지연 로딩한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/media/GalleryImage.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/media/MediaStoreImageSource.java`
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle` (dependencies 블록)
- Test: `android/app/src/test/java/com/traveltrace/app/data/media/MediaStoreImageSourceTest.java`

**Interfaces:**
- Consumes: Task 1 `AppExecutors`, `Callback<T>`
- Produces: `MediaStoreImageSource.loadRecent(int limit, Callback<List<GalleryImage>>)`; `GalleryImage` 공개 필드 `id`(long), `contentUri`(`Uri`), `displayName`(String), `dateTakenUtc`(`Long`, nullable)

- [ ] **Step 1: 버전 카탈로그에 Glide 를 추가한다**

`android/gradle/libs.versions.toml` 의 `[versions]` 블록에 추가:

```toml
glide = "4.16.0"
```

같은 파일 `[libraries]` 블록에 추가:

```toml
glide = { group = "com.github.bumptech.glide", name = "glide", version.ref = "glide" }
```

`android/app/build.gradle` 의 `dependencies { ... }` 에서 `implementation libs.places` 바로 다음 줄에 추가:

```groovy
    // 썸네일 로딩. 생성 API(annotationProcessor)는 쓰지 않는다 — Glide.with() 만 직접 호출.
    implementation libs.glide
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/media/MediaStoreImageSourceTest.java`:

```java
package com.traveltrace.app.data.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.database.MatrixCursor;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class MediaStoreImageSourceTest {

    private Context ctx;
    private AppExecutors executors;
    private MediaStoreImageSource source;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        source = new MediaStoreImageSource(ctx, executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
    }

    private void seed(Object[]... rows) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN});
        for (Object[] row : rows) {
            cursor.addRow(row);
        }
        ContentResolver resolver = ctx.getContentResolver();
        ShadowContentResolver shadow = Shadows.shadowOf(resolver);
        shadow.setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);
    }

    private List<GalleryImage> load(int limit) {
        AtomicReference<List<GalleryImage>> box = new AtomicReference<>();
        source.loadRecent(limit, box::set);
        ShadowLooper.idleMainLooper();
        return box.get();
    }

    @Test
    public void mapsCursorRowsToGalleryImages() {
        seed(new Object[]{11L, "a.jpg", 1_700_000_000_000L});

        List<GalleryImage> images = load(100);

        assertEquals(1, images.size());
        GalleryImage first = images.get(0);
        assertEquals(11L, first.id);
        assertEquals("a.jpg", first.displayName);
        assertEquals(Long.valueOf(1_700_000_000_000L), first.dateTakenUtc);
        assertTrue("contentUri 는 _ID 로 만들어져야 한다",
                first.contentUri.toString().endsWith("/11"));
    }

    @Test
    public void zeroDateTakenBecomesNullNotEpoch() {
        seed(new Object[]{12L, "b.jpg", 0L});

        assertNull("DATE_TAKEN 0 은 '없음'이지 1970년이 아니다",
                load(100).get(0).dateTakenUtc);
    }

    @Test
    public void respectsTheLimit() {
        seed(new Object[]{1L, "a.jpg", 3_000L},
                new Object[]{2L, "b.jpg", 2_000L},
                new Object[]{3L, "c.jpg", 1_000L});

        assertEquals(2, load(2).size());
    }

    @Test
    public void emptyGalleryYieldsEmptyListNotNull() {
        seed();
        assertTrue(load(100).isEmpty());
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MediaStoreImageSourceTest*'`
Expected: FAIL — `error: cannot find symbol: class MediaStoreImageSource`

- [ ] **Step 4: `GalleryImage` 를 만든다**

`data/media/GalleryImage.java`:

```java
package com.traveltrace.app.data.media;

import android.net.Uri;

import androidx.annotation.Nullable;

/** MediaStore 행 1개. 썸네일 비트맵은 들고 있지 않다 — Glide 가 바인드 시점에 읽는다. */
public class GalleryImage {

    public final long id;
    public final Uri contentUri;
    public final String displayName;

    /** MediaStore 가 아는 촬영 시각(UTC millis). 모르면 null. EXIF 가 최종 판정한다. */
    @Nullable
    public final Long dateTakenUtc;

    public GalleryImage(long id, Uri contentUri, String displayName,
                        @Nullable Long dateTakenUtc) {
        this.id = id;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.dateTakenUtc = dateTakenUtc;
    }
}
```

- [ ] **Step 5: `MediaStoreImageSource` 를 만든다**

`data/media/MediaStoreImageSource.java`:

```java
package com.traveltrace.app.data.media;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.domain.Callback;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 갤러리 이미지 목록. PRD §4.1: Photo Picker 대신 MediaStore 를 쓰는 이유는
 * 캐시·재조회에 필요한 <em>안정적 _ID</em> 때문이다.
 *
 * <p>커서에서 뽑는 건 long/문자열뿐이라 수천 장도 수 MB 미만 — 페이징 없이 1회 전량 읽고,
 * 비싼 비트맵은 Glide 가 바인드 시점에 지연 로딩한다.
 */
@Singleton
public class MediaStoreImageSource {

    private static final String[] PROJECTION = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN};

    private final Context context;
    private final AppExecutors executors;

    @Inject
    public MediaStoreImageSource(@ApplicationContext Context context, AppExecutors executors) {
        this.context = context;
        this.executors = executors;
    }

    /** 최신 촬영순으로 최대 limit 장. 권한이 없으면 빈 목록이 온다(예외를 던지지 않는다). */
    public void loadRecent(int limit, Callback<List<GalleryImage>> callback) {
        executors.io().execute(() -> {
            List<GalleryImage> images = query(limit);
            executors.mainThread().execute(() -> callback.onResult(images));
        });
    }

    private List<GalleryImage> query(int limit) {
        List<GalleryImage> images = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC";

        try (Cursor cursor = context.getContentResolver()
                .query(collection, PROJECTION, null, null, sort)) {
            if (cursor == null) return images;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

            while (cursor.moveToNext() && images.size() < limit) {
                long id = cursor.getLong(idCol);
                long taken = cursor.isNull(takenCol) ? 0L : cursor.getLong(takenCol);
                images.add(new GalleryImage(
                        id,
                        ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameCol),
                        // 0 은 "촬영 시각 모름"이다 — 1970년으로 저장하면 정렬이 망가진다.
                        taken > 0L ? taken : null));
            }
        } catch (SecurityException denied) {
            // 권한이 아직 없거나 철회된 상태. 빈 목록으로 조용히 끝낸다 — 안내는 UI 소관.
            return new ArrayList<>();
        }
        return images;
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MediaStoreImageSourceTest*'`
Expected: PASS (4 tests)

- [ ] **Step 7: 커밋**

```bash
cd android && git add gradle/libs.versions.toml app/build.gradle \
  app/src/main/java/com/traveltrace/app/data/media/ \
  app/src/test/java/com/traveltrace/app/data/media/
git commit -m "feat: read the gallery through MediaStore and add Glide for thumbnails"
```

---

## Task 5: MediaPermissionController — 허용 / 거부 / 부분허용 3경로

`minSdk 33` 이라 `READ_MEDIA_IMAGES` 는 무조건 필요하고, **API 34+ 에서는 사용자가 "선택한 사진만" 을 고를 수 있다.** 그 경우 `READ_MEDIA_IMAGES` 는 거부되고 `READ_MEDIA_VISUAL_USER_SELECTED` 만 허용된다 — 이걸 "거부"로 오판하면 갤러리가 통째로 안 보인다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/photo/MediaPermissionController.java`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/photo/MediaPermissionControllerTest.java`

**Interfaces:**
- Consumes: 없음 (Android 프레임워크만)
- Produces: `MediaPermissionController.evaluate(Context)` → `MediaPermissionController.State` (`GRANTED` / `PARTIAL` / `DENIED`), `MediaPermissionController.requiredPermissions()` → `String[]`

- [ ] **Step 1: 매니페스트에 부분 접근 권한을 추가한다**

`android/app/src/main/AndroidManifest.xml`, `ACCESS_MEDIA_LOCATION` 선언 바로 다음에 추가:

```xml
    <!-- Android 14+ "선택한 사진만 접근". 이게 없으면 부분 허용이 전면 거부로 보인다. -->
    <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/photo/MediaPermissionControllerTest.java`:

```java
package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

@RunWith(RobolectricTestRunner.class)
public class MediaPermissionControllerTest {

    private Application app;
    private ShadowApplication shadowApp;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        shadowApp = Shadows.shadowOf(app);
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void fullGrantIsGranted() {
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);

        assertEquals(MediaPermissionController.State.GRANTED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void userSelectedOnlyIsPartialNotDenied() {
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        shadowApp.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);

        assertEquals("선택한 사진만 허용은 '거부'가 아니라 '부분'이다",
                MediaPermissionController.State.PARTIAL,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void nothingGrantedIsDenied() {
        shadowApp.denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals(MediaPermissionController.State.DENIED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void onApi33ThereIsNoPartialState() {
        shadowApp.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);

        assertEquals("API 33 엔 부분 접근이 없다 — 거부는 거부다",
                MediaPermissionController.State.DENIED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void api34RequestsBothImagesAndUserSelected() {
        assertArrayEquals(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        Manifest.permission.ACCESS_MEDIA_LOCATION},
                MediaPermissionController.requiredPermissions());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void api33DoesNotRequestUserSelected() {
        assertArrayEquals(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.ACCESS_MEDIA_LOCATION},
                MediaPermissionController.requiredPermissions());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void mediaLocationIsNotPartOfTheReadVerdict() {
        // ACCESS_MEDIA_LOCATION 이 없어도 목록은 보여야 한다 — 없으면 GPS 만 못 읽는다.
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        shadowApp.denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);

        assertEquals(MediaPermissionController.State.GRANTED,
                MediaPermissionController.evaluate(app));
        assertEquals(PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MediaPermissionControllerTest*'`
Expected: FAIL — `error: cannot find symbol: class MediaPermissionController`

- [ ] **Step 4: `MediaPermissionController` 를 만든다**

`ui/photo/MediaPermissionController.java`:

```java
package com.traveltrace.app.ui.photo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * 미디어 권한 판정 (PRD §4.1, plan/05).
 *
 * <p>minSdk 33 이므로 READ_MEDIA_IMAGES 는 항상 필요하다. API 34+ 에서 사용자가
 * "선택한 사진만" 을 고르면 READ_MEDIA_IMAGES 는 <em>거부</em>되고
 * READ_MEDIA_VISUAL_USER_SELECTED 만 허용된다 — 이 조합을 PARTIAL 로 읽지 않으면
 * 갤러리가 통째로 비어 보인다.
 *
 * <p>ACCESS_MEDIA_LOCATION 은 판정에 넣지 않는다. 없으면 원본 GPS 를 못 읽을 뿐
 * (plan/06 의 setRequireOriginal 실패 경로) 목록 자체는 정상이다.
 */
public final class MediaPermissionController {

    public enum State {
        /** 전체 갤러리 접근 가능. */
        GRANTED,
        /** 사용자가 고른 사진만 보인다 — "더 선택하기" 유도가 필요하다. */
        PARTIAL,
        /** 아무것도 못 읽는다 — 차단 화면 + 설정 이동 유도. */
        DENIED
    }

    private MediaPermissionController() {}

    private static boolean supportsPartialAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** 요청할 권한 묶음. API 34+ 에서만 VISUAL_USER_SELECTED 를 함께 요청한다. */
    public static String[] requiredPermissions() {
        if (supportsPartialAccess()) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        return new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION};
    }

    public static State evaluate(Context context) {
        if (granted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
            return State.GRANTED;
        }
        if (supportsPartialAccess()
                && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            return State.PARTIAL;
        }
        return State.DENIED;
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MediaPermissionControllerTest*'`
Expected: PASS (7 tests)

- [ ] **Step 6: 커밋**

```bash
cd android && git add app/src/main/AndroidManifest.xml \
  app/src/main/java/com/traveltrace/app/ui/photo/MediaPermissionController.java \
  app/src/test/java/com/traveltrace/app/ui/photo/MediaPermissionControllerTest.java
git commit -m "feat: classify media permission into granted / partial / denied"
```

---

## Task 6: Tile 에 사진 식별자 붙이기 (골든 보존 리팩터)

`Tile` 에 `mediaStoreId`/`contentUri` 를 **추가**한다 — `toneColor` 를 교체하지 않는다. 픽스처는 `contentUri` 를 null 로 넘기므로 기존 색블록 경로로 그려지고 `select_default.png` 골든이 그대로 통과한다. 실행 시에만 Glide 가 썸네일을 덮어쓴다.

> **Global Constraints 의 파급 규칙 적용:** `Tile` 필드가 늘어나므로 ① `ScreenFixtures.tile()` 18곳, ② `PhotoSelectionUiState.withToggled()` 복제, ③ `PhotoSelectionRendererTest` 를 함께 확인한다. **②를 빠뜨리면 타일을 탭할 때마다 썸네일이 색블록으로 되돌아간다.**

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionUiState.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoGridAdapter.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionUiStateTest.java`

**Interfaces:**
- Consumes: Task 4 `GalleryImage`
- Produces: `PhotoSelectionUiState.Tile(int toneColor, String label, boolean selected, long mediaStoreId, Uri contentUri)`; `Tile.mediaStoreId`, `Tile.contentUri`; `PhotoSelectionUiState.selectedMediaStoreIds()` → `List<Long>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionUiStateTest.java`:

```java
package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionUiStateTest {

    private static PhotoSelectionUiState twoRealTiles() {
        List<PhotoSelectionUiState.Tile> tiles = Arrays.asList(
                new PhotoSelectionUiState.Tile(
                        0xFFDBE4EE, null, true, 11L, Uri.parse("content://media/11")),
                new PhotoSelectionUiState.Tile(
                        0xFFE8E0D6, "개선문", false, 22L, Uri.parse("content://media/22")));
        return new PhotoSelectionUiState("기간", 100, tiles);
    }

    @Test
    public void toggledTileKeepsItsPhotoIdentity() {
        PhotoSelectionUiState next = twoRealTiles().withToggled(0);

        PhotoSelectionUiState.Tile toggled = next.tiles.get(0);
        assertEquals("탭해도 선택 상태만 바뀐다", false, toggled.selected);
        assertEquals("mediaStoreId 가 사라지면 저장이 엉뚱한 사진을 가리킨다",
                11L, toggled.mediaStoreId);
        assertEquals("contentUri 가 사라지면 썸네일이 색블록으로 되돌아간다",
                Uri.parse("content://media/11"), toggled.contentUri);
        assertEquals("톤 색도 보존된다", 0xFFDBE4EE, toggled.toneColor);
        assertNull(toggled.label);
    }

    @Test
    public void selectedMediaStoreIdsReturnsOnlySelectedInOrder() {
        assertEquals(Arrays.asList(11L), twoRealTiles().selectedMediaStoreIds());

        PhotoSelectionUiState bothOn = twoRealTiles().withToggled(1);
        assertEquals(Arrays.asList(11L, 22L), bothOn.selectedMediaStoreIds());
    }

    @Test
    public void fixtureTilesCarryNoUriSoTheColorBlockPathStillRenders() {
        PhotoSelectionUiState fixture =
                com.traveltrace.app.ui.preview.ScreenFixtures.photoSelection();

        assertNull("픽스처 타일엔 실제 사진이 없다 — 골든이 색블록으로 유지되는 근거",
                fixture.tiles.get(0).contentUri);
        assertEquals(18, fixture.tiles.size());
        assertEquals(16, fixture.selectedCount());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*PhotoSelectionUiStateTest*'`
Expected: FAIL — `constructor Tile in class Tile cannot be applied to given types`

- [ ] **Step 3: `PhotoSelectionUiState` 를 고친다**

`ui/photo/PhotoSelectionUiState.java` (전체 교체):

```java
package com.traveltrace.app.ui.photo;

import android.net.Uri;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SELECT 화면이 렌더할 불변 상태. */
public final class PhotoSelectionUiState {

    public final String periodLabel;
    public final int maxCount;
    public final List<Tile> tiles;

    public PhotoSelectionUiState(String periodLabel, int maxCount, List<Tile> tiles) {
        this.periodLabel = periodLabel;
        this.maxCount = maxCount;
        this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
    }

    public int selectedCount() {
        int n = 0;
        for (Tile t : tiles) {
            if (t.selected) n++;
        }
        return n;
    }

    /** 분석에 넘길 사진 식별자. 화면 순서를 그대로 유지한다. */
    public List<Long> selectedMediaStoreIds() {
        List<Long> ids = new ArrayList<>();
        for (Tile t : tiles) {
            if (t.selected) ids.add(t.mediaStoreId);
        }
        return ids;
    }

    /**
     * index 타일의 선택 상태만 뒤집은 새 상태를 만든다 (원본 불변).
     *
     * <p>사진 식별자(mediaStoreId·contentUri)를 반드시 함께 실어 나른다 — 빠뜨리면
     * 탭할 때마다 썸네일이 색블록으로 되돌아가고 저장이 엉뚱한 사진을 가리킨다.
     */
    public PhotoSelectionUiState withToggled(int index) {
        List<Tile> next = new ArrayList<>(tiles);
        Tile t = next.get(index);
        next.set(index, new Tile(
                t.toneColor, t.label, !t.selected, t.mediaStoreId, t.contentUri));
        return new PhotoSelectionUiState(periodLabel, maxCount, next);
    }

    /** 그리드 타일 1개. contentUri 가 null 이면 톤 색으로 그린다(픽스처·프리뷰 경로). */
    public static final class Tile {
        @ColorInt public final int toneColor;
        /** 라벨 pill 문구. 없으면 null. */
        @Nullable public final String label;
        public final boolean selected;
        public final long mediaStoreId;
        /** 실제 사진. 픽스처에서는 null. */
        @Nullable public final Uri contentUri;

        public Tile(@ColorInt int toneColor, @Nullable String label, boolean selected,
                    long mediaStoreId, @Nullable Uri contentUri) {
            this.toneColor = toneColor;
            this.label = label;
            this.selected = selected;
            this.mediaStoreId = mediaStoreId;
            this.contentUri = contentUri;
        }
    }
}
```

- [ ] **Step 4: `ScreenFixtures.tile()` 을 새 시그니처에 맞춘다**

`ui/preview/ScreenFixtures.java` 의 `tile` 헬퍼(68–70행)를 교체:

```java
    /** 픽스처는 실제 사진이 없다 — mediaStoreId 0, contentUri null 로 톤 색 경로를 탄다. */
    private static PhotoSelectionUiState.Tile tile(int tone, String label, boolean selected) {
        return new PhotoSelectionUiState.Tile(tone, label, selected, 0L, null);
    }
```

> 호출부 18곳은 헬퍼를 거치므로 수정이 필요 없다.

- [ ] **Step 5: `PhotoGridAdapter` 에 Glide 경로를 붙인다**

`ui/photo/PhotoGridAdapter.java` 의 `onBindViewHolder` 안, `b.photoTile.setCardBackgroundColor(tile.toneColor);` (61행) 다음에 삽입:

```java
        // 실제 사진이 있으면 썸네일로 덮고, 없으면(픽스처·프리뷰) 톤 색만 남긴다.
        if (tile.contentUri == null) {
            com.bumptech.glide.Glide.with(b.tileImage).clear(b.tileImage);
            b.tileImage.setImageDrawable(null);
            b.tileImage.setVisibility(View.GONE);
        } else {
            b.tileImage.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(b.tileImage)
                    .load(tile.contentUri)
                    .centerCrop()
                    .into(b.tileImage);
        }
```

같은 파일 클래스 주석(17–21행)을 교체:

```java
/**
 * 3열 사진 그리드. contentUri 가 있으면 Glide 로 썸네일을, 없으면(픽스처) 톤 색을 그린다.
 * 선택 링/불투명도는 프로토타입 t.style 을 그대로 옮긴 것:
 * 선택 = 3dp 파란 링 + alpha 1, 해제 = 1dp 옅은 테두리 + alpha .5
 */
```

- [ ] **Step 6: 타일 레이아웃에 ImageView 를 추가한다**

`android/app/src/main/res/layout/item_photo_tile.xml` 의 `MaterialCardView`(`@id/photoTile`) 안, 기존 자식들보다 **먼저** 오도록 삽입한다 (체크 배지·라벨이 썸네일 위에 와야 한다):

```xml
        <ImageView
            android:id="@+id/tileImage"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@string/select_tile_desc"
            android:scaleType="centerCrop"
            android:visibility="gone" />
```

- [ ] **Step 7: 테스트가 통과하고 골든이 그대로인지 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. **`select_default.png` 는 재기록 없이 verify 통과해야 한다.** 실패하면 `tileImage` 가 GONE 이 아니거나 레이아웃 삽입 위치가 틀린 것이다.

- [ ] **Step 8: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/photo/ \
  app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java \
  app/src/main/res/layout/item_photo_tile.xml \
  app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionUiStateTest.java
git commit -m "feat: carry photo identity on selection tiles without disturbing the golden"
```

---

## Task 7: SELECT 실데이터 — 권한 흐름 + 갤러리 로딩 + 선택 인계

`PhotoSelectionViewModel` 의 공급원을 `ScreenFixtures` 에서 `MediaStoreImageSource` 로 바꾸고, 선택된 `_ID` 목록을 `SelectionSession` 에 실어 ANALYZE 로 넘긴다. 100장 상한과 기간 요약도 실제 데이터에서 계산한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/selection/SelectionSession.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionFragment.java`
- Modify: `android/app/src/main/res/layout/fragment_photo_selection.xml` (**`toastPill` 추가** — 없으면 이 화면의 모든 안내가 조용히 사라진다)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/selection/SelectionSessionTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModelTest.java`

**Interfaces:**
- Consumes: Task 4 `MediaStoreImageSource`/`GalleryImage`, Task 5 `MediaPermissionController`, Task 6 `Tile`
- Produces: `SelectionSession.put(List<Long>)`, `SelectionSession.ids()` → `List<Long>`, `SelectionSession.isEmpty()`, `SelectionSession.clear()`; `PhotoSelectionViewModel.load()`, `PhotoSelectionViewModel.commitSelection()` → `boolean`

- [ ] **Step 1: 문자열 리소스를 추가한다**

`android/app/src/main/res/values/strings.xml` 의 SELECT 블록(20행 `select_hint` 부근) 다음에 추가:

```xml
    <string name="select_period_range">%1$s – %2$s · 사진 %3$d장</string>
    <string name="select_period_empty">사진이 없어요</string>
    <string name="select_permission_denied">사진 접근을 허용해야 경로를 그릴 수 있어요</string>
    <string name="select_permission_partial">선택한 사진만 보이는 중이에요</string>
    <string name="select_none_selected">사진을 한 장 이상 골라 주세요</string>
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/selection/SelectionSessionTest.java`:

```java
package com.traveltrace.app.ui.selection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SelectionSessionTest {

    @Test
    public void startsEmpty() {
        assertTrue(new SelectionSession().isEmpty());
    }

    @Test
    public void putThenReadRoundTrips() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L, 22L, 33L));

        assertFalse(session.isEmpty());
        assertEquals(Arrays.asList(11L, 22L, 33L), session.ids());
    }

    @Test
    public void returnedListIsADefensiveCopy() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L));

        List<Long> ids = session.ids();
        ids.add(99L);

        assertEquals("외부에서 목록을 바꿔도 세션은 그대로여야 한다",
                Arrays.asList(11L), session.ids());
    }

    @Test
    public void clearEmptiesTheSession() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L));
        session.clear();

        assertTrue(session.isEmpty());
        assertTrue(session.ids().isEmpty());
    }
}
```

`android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModelTest.java`:

```java
package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.MatrixCursor;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.ui.selection.SelectionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionViewModelTest {

    private AppExecutors executors;
    private SelectionSession session;
    private PhotoSelectionViewModel vm;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        MatrixCursor cursor = new MatrixCursor(new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN});
        cursor.addRow(new Object[]{11L, "a.jpg", 1_718_000_000_000L});
        cursor.addRow(new Object[]{22L, "b.jpg", 1_718_100_000_000L});
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);

        executors = new AppExecutors();
        session = new SelectionSession();
        vm = new PhotoSelectionViewModel(
                ctx, new MediaStoreImageSource(ctx, executors), session);
    }

    @After
    public void tearDown() {
        executors.shutdown();
    }

    private PhotoSelectionUiState loadState() {
        vm.load();
        ShadowLooper.idleMainLooper();
        return vm.state().getValue();
    }

    @Test
    public void loadsGalleryImagesAsSelectedTiles() {
        PhotoSelectionUiState state = loadState();

        assertNotNull(state);
        assertEquals(2, state.tiles.size());
        assertTrue("새로 읽은 사진은 기본 선택 상태다", state.tiles.get(0).selected);
        assertEquals(11L, state.tiles.get(0).mediaStoreId);
        assertNotNull("실제 사진은 contentUri 를 갖는다", state.tiles.get(0).contentUri);
    }

    @Test
    public void togglePreservesIdentityAndUpdatesCount() {
        loadState();
        vm.toggle(0);
        ShadowLooper.idleMainLooper();

        PhotoSelectionUiState state = vm.state().getValue();
        assertEquals(1, state.selectedCount());
        assertEquals("해제해도 사진 식별자는 남는다", 11L, state.tiles.get(0).mediaStoreId);
    }

    @Test
    public void commitSelectionPushesSelectedIdsIntoTheSession() {
        loadState();
        vm.toggle(0);
        ShadowLooper.idleMainLooper();

        assertTrue(vm.commitSelection());
        assertEquals(Arrays.asList(22L), session.ids());
    }

    @Test
    public void commitWithNothingSelectedIsRejected() {
        loadState();
        vm.toggle(0);
        vm.toggle(1);
        ShadowLooper.idleMainLooper();

        assertFalse("한 장도 없으면 분석을 시작할 수 없다", vm.commitSelection());
        assertTrue(session.isEmpty());
    }

    @Test
    public void emptyGalleryProducesAnEmptyStateWithoutCrashing() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(ctx.getContentResolver()).setCursor(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new MatrixCursor(new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN}));

        PhotoSelectionUiState state = loadState();
        assertTrue(state.tiles.isEmpty());
        assertEquals(0, state.selectedCount());
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*SelectionSessionTest*' --tests '*PhotoSelectionViewModelTest*'`
Expected: FAIL — `error: cannot find symbol: class SelectionSession`

- [ ] **Step 4: `SelectionSession` 을 만든다**

`ui/selection/SelectionSession.java`:

```java
package com.traveltrace.app.ui.selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * SELECT → ANALYZE 로 넘기는 선택 사진 목록. 최대 100개의 long 이라 인메모리로 충분하고,
 * plan/05 의 "대용량도 Bundle 직렬화 없이" 요구를 만족한다.
 *
 * <p>프로세스 사망 시에는 비어 있게 된다 — ANALYZE 진입에서 그 상태를 감지해 Home 으로
 * 돌려보낸다(Task 9). 식별자 하나뿐인 tripId 는 여기가 아니라 nav argument 로 나른다.
 */
@Singleton
public class SelectionSession {

    private final List<Long> ids = new ArrayList<>();

    @Inject
    public SelectionSession() {}

    public synchronized void put(List<Long> next) {
        ids.clear();
        ids.addAll(next);
    }

    /** 방어적 복사본. 호출부가 바꿔도 세션은 그대로다. */
    public synchronized List<Long> ids() {
        return new ArrayList<>(ids);
    }

    public synchronized boolean isEmpty() {
        return ids.isEmpty();
    }

    public synchronized void clear() {
        ids.clear();
    }

    /** 테스트·디버그용 읽기 전용 뷰. */
    public synchronized List<Long> readOnly() {
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }
}
```

- [ ] **Step 5: `PhotoSelectionViewModel` 을 실데이터로 바꾼다**

`ui/photo/PhotoSelectionViewModel.java` (전체 교체):

```java
package com.traveltrace.app.ui.photo;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.ui.selection.SelectionSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * SELECT 데이터. 갤러리에서 최근 사진을 읽어 타일로 만들고, 확정된 선택을
 * SelectionSession 에 실어 ANALYZE 로 넘긴다.
 *
 * <p>썸네일 타일에 톤 색이 남아 있는 이유: Glide 로딩 전/실패 시의 placeholder 다.
 */
@HiltViewModel
public class PhotoSelectionViewModel extends ViewModel {

    /** PRD §4.1: 한 번에 최대 ~100장 권장. */
    public static final int MAX_SELECTION = 100;

    /** 그리드에 올릴 최근 사진 상한. 선택 상한(100)보다 넉넉해야 고를 여지가 있다. */
    private static final int GALLERY_PAGE = 500;

    /** Glide 로딩 전 placeholder 로 쓰는 톤 팔레트(프로토타입 TONES 계열). */
    private static final int[] TONES = {
            0xFFDBE4EE, 0xFFE8E0D6, 0xFFDDE8E1, 0xFFE6DDE6, 0xFFE7E1D6, 0xFFD8E1EA};

    private final Context context;
    private final MediaStoreImageSource imageSource;
    private final SelectionSession session;
    private final MutableLiveData<PhotoSelectionUiState> state = new MutableLiveData<>();

    @Inject
    public PhotoSelectionViewModel(@ApplicationContext Context context,
                                   MediaStoreImageSource imageSource,
                                   SelectionSession session) {
        this.context = context;
        this.imageSource = imageSource;
        this.session = session;
    }

    public LiveData<PhotoSelectionUiState> state() {
        return state;
    }

    /** 권한이 확보된 뒤 Fragment 가 호출한다. 여러 번 불러도 안전하다. */
    public void load() {
        imageSource.loadRecent(GALLERY_PAGE, images -> state.setValue(toState(images)));
    }

    private PhotoSelectionUiState toState(List<GalleryImage> images) {
        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            GalleryImage image = images.get(i);
            tiles.add(new PhotoSelectionUiState.Tile(
                    TONES[i % TONES.length],
                    null,
                    // 기본 전체 선택 — 사용자는 "탭하여 제외"한다(프로토타입 문구).
                    i < MAX_SELECTION,
                    image.id,
                    image.contentUri));
        }
        return new PhotoSelectionUiState(periodLabel(images), MAX_SELECTION, tiles);
    }

    /** "2024. 6. 12 – 6. 15 · 사진 94장" 형태. 시각을 모르는 사진은 기간 계산에서 뺀다. */
    private String periodLabel(List<GalleryImage> images) {
        Long earliest = null;
        Long latest = null;
        for (GalleryImage image : images) {
            if (image.dateTakenUtc == null) continue;
            if (earliest == null || image.dateTakenUtc < earliest) earliest = image.dateTakenUtc;
            if (latest == null || image.dateTakenUtc > latest) latest = image.dateTakenUtc;
        }
        if (earliest == null) {
            return context.getString(R.string.select_period_empty);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy. M. d", Locale.KOREA);
        return context.getString(R.string.select_period_range,
                fmt.format(new Date(earliest)), fmt.format(new Date(latest)), images.size());
    }

    public void toggle(int index) {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return;
        state.setValue(current.withToggled(index));
    }

    /**
     * 선택을 확정해 세션에 싣는다. 한 장도 없으면 false 를 돌려주고 아무것도 하지 않는다
     * — 호출부가 화면 전환을 막는다.
     */
    public boolean commitSelection() {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return false;
        List<Long> ids = current.selectedMediaStoreIds();
        if (ids.isEmpty()) return false;
        session.put(ids);
        return true;
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*SelectionSessionTest*' --tests '*PhotoSelectionViewModelTest*'`
Expected: PASS (9 tests)

- [ ] **Step 7: 레이아웃에 토스트 pill 을 추가한다**

`ToastPresenter.show()` 는 앵커 루트에서 `@id/toastPill` 을 못 찾으면 **조용히 아무것도 하지 않는다.** `fragment_photo_selection.xml` 에는 이 뷰가 없어서(HOME/ANALYZE/MAP 세 레이아웃에만 있다) 권한 안내·선택 안내가 전부 사라진다 — S1 수용 기준의 "3경로 모두 안내"가 깨진다.

`android/app/src/main/res/layout/fragment_photo_selection.xml` 의 루트 `ConstraintLayout` 닫는 태그 **바로 앞**에 추가 (`fragment_home.xml` 의 같은 뷰와 동일한 스펙):

```xml
    <TextView
        android:id="@+id/toastPill"
        style="@style/TextAppearance.TravelTrace.Caption"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="@dimen/toast_bottom_margin"
        android:background="@drawable/bg_toast_pill"
        android:paddingStart="18dp"
        android:paddingTop="11dp"
        android:paddingEnd="18dp"
        android:paddingBottom="11dp"
        android:textColor="@color/text_on_fill"
        android:textFontWeight="600"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />
```

> 이 뷰는 기본 `visibility="gone"` 이라 `select_default.png` 골든에 영향을 주지 않는다. Step 9 에서 확인한다.

- [ ] **Step 8: Fragment 에 권한 흐름을 붙인다**

`ui/photo/PhotoSelectionFragment.java` (전체 교체):

```java
package com.traveltrace.app.ui.photo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import dagger.hilt.android.AndroidEntryPoint;

/** SELECT: 권한 → 갤러리 로딩 → 3열 선택 그리드 → 분석 시작. */
@AndroidEntryPoint
public class PhotoSelectionFragment extends Fragment {

    private FragmentPhotoSelectionBinding binding;
    private PhotoSelectionViewModel vm;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 런처는 STARTED 이전에 등록해야 한다 — onViewCreated 에서 등록하면 예외가 난다.
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> applyPermissionState());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPhotoSelectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(PhotoSelectionViewModel.class);

        binding.selectBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());

        binding.startAnalyzeButton.setOnClickListener(v -> {
            if (vm.commitSelection()) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_photo_to_analysis);
            } else {
                // commitSelection() 이 false 인 유일한 조건은 "한 장도 안 골랐다"이다.
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_none_selected));
            }
        });

        vm.state().observe(getViewLifecycleOwner(), state ->
                PhotoSelectionRenderer.render(binding, state, vm::toggle));

        if (MediaPermissionController.evaluate(requireContext())
                == MediaPermissionController.State.DENIED) {
            permissionLauncher.launch(MediaPermissionController.requiredPermissions());
        } else {
            applyPermissionState();
        }
    }

    /** 권한 판정에 따라 그리드를 채우거나 차단 안내를 띄운다. */
    private void applyPermissionState() {
        if (binding == null) return;
        MediaPermissionController.State state =
                MediaPermissionController.evaluate(requireContext());

        switch (state) {
            case GRANTED:
                vm.load();
                break;
            case PARTIAL:
                // 부분 허용도 읽을 수 있다 — 목록을 채우되 "더 선택하기"를 안내한다.
                vm.load();
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_permission_partial));
                break;
            case DENIED:
            default:
                // 안내를 먼저 띄우고, 사용자가 읽을 수 있게 설정 이동은 CTA 로 넘긴다.
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_permission_denied));
                binding.startAnalyzeButton.setOnClickListener(v -> openAppSettings());
                break;
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        binding = null;
    }
}
```

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. 골든 14장 전부 재기록 없이 통과. `select_default.png` 가 깨지면 `toastPill` 이 `gone` 이 아닌 것이다.

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/selection/ \
  app/src/main/java/com/traveltrace/app/ui/photo/ \
  app/src/main/res/layout/fragment_photo_selection.xml \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/traveltrace/app/ui/selection/ \
  app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModelTest.java
git commit -m "feat: fill SELECT from the real gallery behind the permission gate"
```

---

## Task 8: ExifExtractor + TimeNormalizer — 원본 GPS·촬영 시각 읽기

S1의 핵심. scoped storage 는 기본적으로 위치 EXIF 를 가리므로 `ACCESS_MEDIA_LOCATION` + `MediaStore.setRequireOriginal()` 로 **원본** 스트림을 열어야 한다. 이 경로가 조용히 실패하면 S3에서 모든 사진이 AI 로 흘러 비용이 폭증하므로(plan/06 리스크), 실패를 반드시 세어 노출한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/exif/TimeNormalizer.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/exif/ExifExtractor.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/exif/TimeNormalizerTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/exif/ExifExtractorTest.java`

**Interfaces:**
- Consumes: Task 2 `LocationSource`/`LocationClassification`, Task 3 `PhotoAnalysis`, Task 4 `GalleryImage`
- Produces:
  - `TimeNormalizer.toUtcMillis(String dateTimeOriginal, String offsetOriginal, TimeZone deviceZone)` → `TimeNormalizer.Result` (`utcMillis` `Long`, `hasOffset` `boolean`)
  - `ExifExtractor.extract(GalleryImage)` → `PhotoAnalysis`
  - `ExifExtractor.originalAccessFailures()` → `int`

- [ ] **Step 1: `TimeNormalizer` 의 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/exif/TimeNormalizerTest.java`:

```java
package com.traveltrace.app.data.exif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.TimeZone;

public class TimeNormalizerTest {

    private static final TimeZone SEOUL = TimeZone.getTimeZone("Asia/Seoul");

    @Test
    public void offsetTagWinsOverTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "+02:00", SEOUL);

        assertTrue(r.hasOffset);
        // 2024-06-12T10:12:00+02:00 == 2024-06-12T08:12:00Z
        assertEquals(Long.valueOf(1_718_179_920_000L), r.utcMillis);
    }

    @Test
    public void missingOffsetFallsBackToTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", null, SEOUL);

        assertFalse("폴백이었음을 기록해야 경고를 띄울 수 있다", r.hasOffset);
        // 2024-06-12T10:12:00+09:00 == 2024-06-12T01:12:00Z
        assertEquals(Long.valueOf(1_718_154_720_000L), r.utcMillis);
    }

    @Test
    public void negativeOffsetIsHandled() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "-05:00", SEOUL);

        assertTrue(r.hasOffset);
        // 2024-06-12T10:12:00-05:00 == 2024-06-12T15:12:00Z
        assertEquals(Long.valueOf(1_718_205_120_000L), r.utcMillis);
    }

    @Test
    public void absentDateTimeYieldsNullNotZero() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(null, "+02:00", SEOUL);

        assertNull("시각을 모르면 null 이다 — 0(1970)으로 채우면 정렬이 망가진다", r.utcMillis);
        assertFalse(r.hasOffset);
    }

    @Test
    public void malformedDateTimeYieldsNull() {
        assertNull(TimeNormalizer.toUtcMillis("어제 오후", null, SEOUL).utcMillis);
        assertNull(TimeNormalizer.toUtcMillis("", null, SEOUL).utcMillis);
    }

    @Test
    public void malformedOffsetDegradesToTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "이상한값", SEOUL);

        assertFalse("오프셋을 못 읽으면 폴백으로 취급한다", r.hasOffset);
        assertEquals(Long.valueOf(1_718_154_720_000L), r.utcMillis);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*TimeNormalizerTest*'`
Expected: FAIL — `error: cannot find symbol: class TimeNormalizer`

- [ ] **Step 3: `TimeNormalizer` 를 만든다**

`data/exif/TimeNormalizer.java`:

```java
package com.traveltrace.app.data.exif;

import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXIF 촬영 시각 → UTC millis (PRD §4.2 시각 정규화).
 *
 * <p>규칙: OffsetTimeOriginal 이 있으면 그것을 쓰고, 없으면 <em>기기 타임존</em>으로
 * 폴백하되 폴백이었음을 기록한다(경고 1회의 근거). 폴백은 절대 분석을 막지 않는다
 * — plan/06 의 무타임존 폴백 계약.
 *
 * <p>여행 기준 타임존을 사용자에게 확정받는 시트는 S4 소관이다. 그 덕에 S1 은
 * 좌표→타임존 오프라인 데이터 의존성을 들이지 않는다.
 */
public final class TimeNormalizer {

    /** EXIF DateTimeOriginal 의 표준 형식. */
    private static final String EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss";

    /** "+09:00" / "-05:00" 형태만 받는다. */
    private static final Pattern OFFSET = Pattern.compile("^([+-])(\\d{2}):(\\d{2})$");

    public static final class Result {
        /** UTC millis. 시각을 못 읽었으면 null. */
        @Nullable public final Long utcMillis;
        /** EXIF 오프셋 태그를 실제로 썼는지. false 면 기기 타임존 폴백이다. */
        public final boolean hasOffset;

        Result(@Nullable Long utcMillis, boolean hasOffset) {
            this.utcMillis = utcMillis;
            this.hasOffset = hasOffset;
        }
    }

    private TimeNormalizer() {}

    public static Result toUtcMillis(@Nullable String dateTimeOriginal,
                                     @Nullable String offsetOriginal,
                                     TimeZone deviceZone) {
        if (dateTimeOriginal == null || dateTimeOriginal.trim().isEmpty()) {
            return new Result(null, false);
        }

        TimeZone zone = parseOffset(offsetOriginal);
        boolean hasOffset = zone != null;
        if (zone == null) zone = deviceZone;

        SimpleDateFormat fmt = new SimpleDateFormat(EXIF_PATTERN, Locale.US);
        fmt.setTimeZone(zone);
        fmt.setLenient(false);
        try {
            Date parsed = fmt.parse(dateTimeOriginal);
            return new Result(parsed == null ? null : parsed.getTime(),
                    parsed != null && hasOffset);
        } catch (ParseException malformed) {
            return new Result(null, false);
        }
    }

    /** 못 읽는 오프셋은 null 로 돌려 기기 타임존 폴백을 타게 한다. */
    @Nullable
    private static TimeZone parseOffset(@Nullable String offsetOriginal) {
        if (offsetOriginal == null) return null;
        Matcher m = OFFSET.matcher(offsetOriginal.trim());
        if (!m.matches()) return null;
        return TimeZone.getTimeZone("GMT" + m.group(1) + m.group(2) + ":" + m.group(3));
    }
}
```

- [ ] **Step 4: `TimeNormalizer` 테스트가 통과하는지 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*TimeNormalizerTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: `ExifExtractor` 의 실패하는 테스트를 쓴다**

합성 JPEG 을 테스트 셋업에서 만들어 GPS 태그를 써 넣는다 — 바이너리 픽스처를 커밋하지 않아도 되고, "GPS 가린 파생본" 비교(plan/06 DoD)도 같은 방법으로 만든다.

`android/app/src/test/java/com/traveltrace/app/data/exif/ExifExtractorTest.java`:

```java
package com.traveltrace.app.data.exif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
public class ExifExtractorTest {

    private Context ctx;
    private ExifExtractor extractor;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        extractor = new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul"));
    }

    /**
     * 최소 JPEG 을 만들고 EXIF 를 써 넣은 뒤, 그 파일을 uri 에 물린다.
     * withGps=false 면 GPS 태그를 아예 쓰지 않아 "GPS 가린 파생본"을 흉내낸다.
     */
    private Uri jpegWithExif(String name, boolean withGps, String dateTime, String offset)
            throws Exception {
        File file = new File(ctx.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (withGps) {
            exif.setLatLong(48.8584, 2.2945);
        }
        if (dateTime != null) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        }
        if (offset != null) {
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
        }
        exif.saveAttributes();

        Uri uri = Uri.parse("content://media/external/images/media/" + name.hashCode());
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new java.io.FileInputStream(file));
        return uri;
    }

    private static GalleryImage image(Uri uri, long id, String name) {
        return new GalleryImage(id, uri, name, null);
    }

    @Test
    public void gpsPhotoBecomesPlacedWithRealCoordinates() throws Exception {
        Uri uri = jpegWithExif("gps.jpg", true, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 11L, "gps.jpg"));

        assertEquals(LocationSource.GPS, result.source);
        assertEquals(LocationClassification.PLACED, result.classification);
        assertNotNull(result.lat);
        assertEquals(48.8584, result.lat, 0.0001);
        assertEquals(2.2945, result.lng, 0.0001);
        assertTrue(result.takenAtHasOffset);
        assertEquals(Long.valueOf(1_718_179_920_000L), result.takenAtUtc);
    }

    @Test
    public void photoWithoutGpsBecomesUnknownAndKeepsNullCoordinates() throws Exception {
        Uri uri = jpegWithExif("nogps.jpg", false, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 12L, "nogps.jpg"));

        assertEquals(LocationSource.NONE, result.source);
        assertEquals(LocationClassification.UNKNOWN, result.classification);
        assertNull("좌표가 없으면 null 이어야 한다 — 0.0 이면 (0,0) 핀이 생긴다", result.lat);
        assertNull(result.lng);
    }

    @Test
    public void gpsPhotoWithoutTimeBecomesNoTime() throws Exception {
        Uri uri = jpegWithExif("notime.jpg", true, null, null);

        PhotoAnalysis result = extractor.extract(image(uri, 13L, "notime.jpg"));

        assertEquals(LocationSource.GPS, result.source);
        assertEquals("좌표는 있는데 시각이 없으면 NO_TIME — 경로 순서에서 빠진다",
                LocationClassification.NO_TIME, result.classification);
        assertNotNull(result.lat);
        assertNull(result.takenAtUtc);
    }

    @Test
    public void missingOffsetFallsBackAndIsFlagged() throws Exception {
        Uri uri = jpegWithExif("nooffset.jpg", true, "2024:06:12 10:12:00", null);

        PhotoAnalysis result = extractor.extract(image(uri, 14L, "nooffset.jpg"));

        assertEquals(LocationClassification.PLACED, result.classification);
        assertEquals("기기 타임존(Asia/Seoul) 폴백",
                Long.valueOf(1_718_154_720_000L), result.takenAtUtc);
        assertEquals(false, result.takenAtHasOffset);
    }

    @Test
    public void unreadableStreamIsCountedAndDegradesToUnknown() {
        Uri missing = Uri.parse("content://media/external/images/media/999999");

        PhotoAnalysis result = extractor.extract(image(missing, 99L, "gone.jpg"));

        assertEquals(LocationClassification.UNKNOWN, result.classification);
        assertNull(result.lat);
        assertEquals("원본 접근 실패는 반드시 세어야 한다 — 조용히 삼키면 S3 에서 비용이 폭증한다",
                1, extractor.originalAccessFailures());
    }

    @Test
    public void displayNameAndIdSurviveExtraction() throws Exception {
        Uri uri = jpegWithExif("named.jpg", true, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 42L, "named.jpg"));

        assertEquals(42L, result.mediaStoreId);
        assertEquals("named.jpg", result.displayName);
    }
}
```

- [ ] **Step 6: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ExifExtractorTest*'`
Expected: FAIL — `error: cannot find symbol: class ExifExtractor`

- [ ] **Step 7: `ExifExtractor` 를 만든다**

`data/exif/ExifExtractor.java`:

```java
package com.traveltrace.app.data.exif;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.io.IOException;
import java.io.InputStream;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 사진 1장의 원본 EXIF 에서 GPS·촬영 시각을 읽는다 (PRD §4.2).
 *
 * <p>scoped storage 는 기본적으로 위치 EXIF 를 가리므로 ACCESS_MEDIA_LOCATION +
 * {@link MediaStore#setRequireOriginal(Uri)} 로 <em>원본</em> 스트림을 열어야 한다.
 * 그 경로가 실패하면 일반 스트림으로 폴백하되 반드시 카운트한다 — 조용히 누락되면
 * S3 에서 GPS 있는 사진까지 전부 AI 로 흘러 비용이 폭증한다(plan/06 리스크).
 *
 * <p>S1 은 AI 를 쓰지 않으므로 GPS 가 없으면 그대로 UNKNOWN 으로 끝난다. AI 경로는 S3.
 */
@Singleton
public class ExifExtractor {

    private static final String TAG = "ExifExtractor";

    private final Context context;
    private final TimeZone deviceZone;
    private final AtomicInteger originalAccessFailures = new AtomicInteger();

    @Inject
    public ExifExtractor(@ApplicationContext Context context) {
        this(context, TimeZone.getDefault());
    }

    /** 테스트가 기기 타임존을 고정할 수 있게 하는 생성자. */
    public ExifExtractor(Context context, TimeZone deviceZone) {
        this.context = context;
        this.deviceZone = deviceZone;
    }

    /** 원본 접근에 실패해 GPS 를 못 읽었을 수 있는 사진 수. 완료 요약에 노출한다. */
    public int originalAccessFailures() {
        return originalAccessFailures.get();
    }

    public void resetFailureCount() {
        originalAccessFailures.set(0);
    }

    public PhotoAnalysis extract(GalleryImage image) {
        PhotoAnalysis result = new PhotoAnalysis();
        result.mediaStoreId = image.id;
        result.displayName = image.displayName;
        result.source = LocationSource.NONE;
        result.classification = LocationClassification.UNKNOWN;

        ExifInterface exif = openExif(image.contentUri);
        if (exif == null) {
            return result;
        }

        double[] latLong = exif.getLatLong();
        TimeNormalizer.Result time = TimeNormalizer.toUtcMillis(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                deviceZone);

        result.takenAtUtc = time.utcMillis;
        result.takenAtHasOffset = time.hasOffset;

        if (latLong == null) {
            // GPS 없음 → S1 은 여기서 끝. S3 이 이 사진들을 AI 로 보낸다.
            return result;
        }

        result.lat = latLong[0];
        result.lng = latLong[1];
        result.source = LocationSource.GPS;
        // 좌표는 있는데 시각이 없으면 경로 순서에 넣을 수 없다(PRD §4.6).
        result.classification = time.utcMillis == null
                ? LocationClassification.NO_TIME
                : LocationClassification.PLACED;
        return result;
    }

    /**
     * 원본 스트림으로 ExifInterface 를 연다. setRequireOriginal 이 막히면
     * (권한 미승인·제공자 미지원) 일반 스트림으로 폴백하고 실패를 센다.
     */
    private ExifInterface openExif(Uri uri) {
        Uri original = uri;
        boolean requestedOriginal = true;
        try {
            original = MediaStore.setRequireOriginal(uri);
        } catch (UnsupportedOperationException | SecurityException notOriginal) {
            requestedOriginal = false;
            originalAccessFailures.incrementAndGet();
            Log.w(TAG, "setRequireOriginal unavailable for " + uri, notOriginal);
        }

        try (InputStream in = context.getContentResolver().openInputStream(original)) {
            if (in == null) {
                originalAccessFailures.incrementAndGet();
                return null;
            }
            return new ExifInterface(in);
        } catch (SecurityException denied) {
            // 원본 요청이 거부됐다 — 일반 스트림으로 한 번 더 시도한다(GPS 는 못 읽는다).
            if (requestedOriginal) {
                originalAccessFailures.incrementAndGet();
                return openPlain(uri);
            }
            return null;
        } catch (IOException unreadable) {
            originalAccessFailures.incrementAndGet();
            Log.w(TAG, "unreadable photo " + uri, unreadable);
            return null;
        }
    }

    private ExifInterface openPlain(Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in == null ? null : new ExifInterface(in);
        } catch (IOException | SecurityException failed) {
            Log.w(TAG, "plain stream also failed for " + uri, failed);
            return null;
        }
    }
}
```

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ExifExtractorTest*'`
Expected: PASS (6 tests)

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/data/exif/ \
  app/src/test/java/com/traveltrace/app/data/exif/
git commit -m "feat: read original GPS and shooting time from photo EXIF"
```

---

## Task 9: ANALYZE 실진행 — 배치 EXIF · 취소 · 여행 저장

`AnalysisViewModel` 의 `PREVIEW_ANALYZED = 41` 고정값을 실제 진행률로 바꾼다. 완료 시 여행 이름을 자동 생성해 저장하고 tripId 를 내보낸다. 화면을 벗어나면 잔여 작업을 취소한다 — plan/03·11 이 요구하는 누수 방지.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisFragment.java`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java`

**Interfaces:**
- Consumes: Task 1 `AppExecutors`, Task 3 `PhotoAnalysisRepository`, Task 4 `MediaStoreImageSource`, Task 7 `SelectionSession`, Task 8 `ExifExtractor`
- Produces: `AnalysisViewModel.start()`, `AnalysisViewModel.cancel()`, `AnalysisViewModel.savedTripId()` → `LiveData<String>`, `AnalysisViewModel.abandoned()` → `LiveData<Boolean>`

> **주의:** 기존 `AnalysisViewModelTest` 는 `setDone(boolean)` 을 검증한다. 그 메서드는 유지하되 픽스처 대신 현재 상태를 기반으로 동작하도록 두지 말고, **테스트 쪽을 새 API 로 옮긴다** — 아래 Step 5 에서 처리한다.

- [ ] **Step 1: 문자열 리소스를 추가한다**

`android/app/src/main/res/values/strings.xml` 의 ANALYZE 블록(34행 `analyze_goto_map`) 다음에 추가:

```xml
    <string name="analyze_trip_name">%1$d년 %2$d월 여행</string>
```

> `analyze_done_summary`("경로 %1$d · 위치 미상 %2$d")가 이미 있으므로 위치 미상 안내는 그 리소스가 담당한다 — 별도 문구를 만들지 않는다(결정 #20).

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java`:

```java
package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.ui.selection.SelectionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AnalysisPipelineTest {

    private Context ctx;
    private AppExecutors executors;
    private TravelTraceDatabase db;
    private SelectionSession session;
    private RoomTripRepository tripRepo;
    private AnalysisViewModel vm;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new SelectionSession();
        tripRepo = new RoomTripRepository(db, executors);

        seedGallery();

        vm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    /** 사진 3장: GPS 2장 + GPS 없는 1장. */
    private void seedGallery() throws Exception {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN});
        cursor.addRow(new Object[]{1L, "a.jpg", 1_718_154_720_000L});
        cursor.addRow(new Object[]{2L, "b.jpg", 1_718_158_320_000L});
        cursor.addRow(new Object[]{3L, "c.jpg", 1_718_161_920_000L});
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);

        registerJpeg(1L, true, "2024:06:12 10:12:00");
        registerJpeg(2L, true, "2024:06:12 11:12:00");
        registerJpeg(3L, false, "2024:06:12 12:12:00");
    }

    private void registerJpeg(long id, boolean withGps, String dateTime) throws Exception {
        File file = new File(ctx.getCacheDir(), id + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (withGps) exif.setLatLong(48.85 + id / 100d, 2.29 + id / 100d);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        exif.saveAttributes();

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build();
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new FileInputStream(file));
    }

    /** 백그라운드 작업 + 메인 루퍼 콜백이 모두 소진될 때까지 돌린다. */
    private void drain() {
        for (int i = 0; i < 50; i++) {
            ShadowLooper.idleMainLooper();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ShadowLooper.idleMainLooper();
            if (vm.state().getValue() != null && vm.state().getValue().done) return;
            if (Boolean.TRUE.equals(vm.abandoned().getValue())) return;
        }
    }

    @Test
    public void analyzesEverySelectedPhotoAndReportsProgress() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertNotNull(state);
        assertTrue("완료 상태로 끝나야 한다", state.done);
        assertEquals(3, state.total);
        assertEquals(3, state.analyzed);
        assertEquals(100, state.progressPercent());
    }

    @Test
    public void gpsPhotosBecomeRouteAndTheRestBecomeUnknown() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertEquals("GPS 2장이 경로에 오른다", 2, state.routeCount);
        assertEquals("GPS 없는 1장은 위치 미상", 1, state.unknownCount);
    }

    @Test
    public void savedTripIsReopenableWithTheRouteIntact() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        String tripId = vm.savedTripId().getValue();
        assertNotNull("완료되면 tripId 가 나와야 한다", tripId);

        AtomicReference<TripDetail> box = new AtomicReference<>();
        tripRepo.open(tripId, box::set);
        ShadowLooper.idleMainLooper();

        TripDetail detail = box.get();
        assertNotNull(detail);
        assertEquals(2, detail.stops.size());
        assertEquals("시각순 정렬", 1L, detail.stops.get(0).mediaStoreId);
        assertEquals(1, detail.unknownCount);
    }

    @Test
    public void selectionIsClearedAfterSaving() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        assertTrue("저장이 끝나면 세션을 비워 재진입 시 중복 저장을 막는다", session.isEmpty());
    }

    @Test
    public void emptySessionAbandonsInsteadOfAnalyzing() {
        vm.start();
        ShadowLooper.idleMainLooper();

        assertTrue("프로세스 사망 후 재진입 — Home 으로 돌려보낸다",
                Boolean.TRUE.equals(vm.abandoned().getValue()));
        assertNull(vm.savedTripId().getValue());
    }

    @Test
    public void cancelStopsTheRunAndLeavesNothingSaved() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        vm.cancel();
        drain();

        assertNull("취소하면 여행이 저장되지 않는다", vm.savedTripId().getValue());
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisPipelineTest*'`
Expected: FAIL — `constructor AnalysisViewModel in class AnalysisViewModel cannot be applied to given types`

- [ ] **Step 4: `AnalysisViewModel` 을 실진행으로 바꾼다**

`ui/analysis/AnalysisViewModel.java` (전체 교체):

```java
package com.traveltrace.app.ui.analysis;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;
import com.traveltrace.app.ui.selection.SelectionSession;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * ANALYZE 진행. 선택된 사진을 EXIF 로 훑어 진행률을 올리고, 끝나면 여행으로 저장한다.
 *
 * <p>S1 엔 AI 가 없으므로 "현재 인식 텍스트" 자리에는 처리 중인 <em>파일명</em>을 보여준다
 * — MediaStore DISPLAY_NAME 을 이미 쿼리하므로 추가 비용이 없다.
 *
 * <p>화면을 벗어나면 {@link #cancel()} 로 잔여 작업을 멈춘다. 취소 플래그를 매 사진마다
 * 확인하므로 진행 중이던 배치가 DB 를 건드리지 않고 끝난다.
 */
@HiltViewModel
public class AnalysisViewModel extends ViewModel {

    private final Context context;
    private final MediaStoreImageSource imageSource;
    private final ExifExtractor extractor;
    private final PhotoAnalysisRepository analysisRepository;
    private final SelectionSession session;
    private final AppExecutors executors;

    private final MutableLiveData<AnalysisUiState> state = new MutableLiveData<>();
    private final MutableLiveData<String> savedTripId = new MutableLiveData<>();
    private final MutableLiveData<Boolean> abandoned = new MutableLiveData<>(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Inject
    public AnalysisViewModel(@ApplicationContext Context context,
                             MediaStoreImageSource imageSource,
                             ExifExtractor extractor,
                             PhotoAnalysisRepository analysisRepository,
                             SelectionSession session,
                             AppExecutors executors) {
        this.context = context;
        this.imageSource = imageSource;
        this.extractor = extractor;
        this.analysisRepository = analysisRepository;
        this.session = session;
        this.executors = executors;
    }

    public LiveData<AnalysisUiState> state() {
        return state;
    }

    /** 저장이 끝나면 새 tripId 가 실린다 — Fragment 가 이걸 들고 MAP 으로 간다. */
    public LiveData<String> savedTripId() {
        return savedTripId;
    }

    /** 선택 세션이 비어 분석할 게 없는 상태(프로세스 사망 후 재진입). */
    public LiveData<Boolean> abandoned() {
        return abandoned;
    }

    /** 중복 실행을 막으며 1회만 시작한다(회전 시 재호출되어도 안전). */
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        List<Long> selectedIds = session.ids();
        if (selectedIds.isEmpty()) {
            abandoned.setValue(true);
            return;
        }

        extractor.resetFailureCount();
        state.setValue(new AnalysisUiState(0, selectedIds.size(), false, "", 0, 0));

        Set<Long> wanted = new HashSet<>(selectedIds);
        imageSource.loadRecent(Integer.MAX_VALUE, images -> {
            List<GalleryImage> targets = new ArrayList<>();
            for (GalleryImage image : images) {
                if (wanted.contains(image.id)) targets.add(image);
            }
            if (targets.isEmpty()) {
                abandoned.setValue(true);
                return;
            }
            executors.io().execute(() -> run(targets));
        });
    }

    /** 화면 이탈 시 호출. 진행 중인 배치가 저장 없이 끝난다. */
    public void cancel() {
        cancelled.set(true);
    }

    private void run(List<GalleryImage> targets) {
        List<PhotoAnalysis> results = new ArrayList<>();
        int total = targets.size();

        for (int i = 0; i < total; i++) {
            if (cancelled.get()) return;

            GalleryImage image = targets.get(i);
            PhotoAnalysis analysis = extractor.extract(image);
            results.add(analysis);

            int analyzed = i + 1;
            int placed = countPlaced(results);
            int unknown = countUnknown(results);
            String currentName = image.displayName;
            executors.mainThread().execute(() -> state.setValue(
                    new AnalysisUiState(analyzed, total, false, currentName, placed, unknown)));
        }

        if (cancelled.get()) return;

        String name = tripName(context, results);
        String zoneId = TimeZone.getDefault().getID();
        analysisRepository.saveTrip(name, zoneId, results, tripId -> {
            if (cancelled.get()) return;
            session.clear();
            state.setValue(new AnalysisUiState(
                    total, total, true, name, countPlaced(results), countUnknown(results)));
            savedTripId.setValue(tripId);
        });
    }

    private static int countPlaced(List<PhotoAnalysis> results) {
        int n = 0;
        for (PhotoAnalysis a : results) {
            if (a.classification == LocationClassification.PLACED) n++;
        }
        return n;
    }

    private static int countUnknown(List<PhotoAnalysis> results) {
        int n = 0;
        for (PhotoAnalysis a : results) {
            if (a.classification == LocationClassification.UNKNOWN) n++;
        }
        return n;
    }

    /** 원본 접근 실패 수 — 완료 안내에 덧붙인다(조용한 GPS 누락 감지). */
    public int originalAccessFailures() {
        return extractor.originalAccessFailures();
    }

    /**
     * "2024년 6월 여행". 도시명은 역지오코딩이 필요해 S1 범위 밖이라 넣지 않는다
     * (plan/15 의 "YYYY 도시 여행" 제안에서 의도적으로 이탈).
     *
     * <p>저장되는 값이지만 그대로 화면에 뜨므로 문자열 리소스로 조립한다.
     */
    private static String tripName(Context context, List<PhotoAnalysis> results) {
        Long earliest = null;
        for (PhotoAnalysis a : results) {
            if (a.takenAtUtc == null) continue;
            if (earliest == null || a.takenAtUtc < earliest) earliest = a.takenAtUtc;
        }
        Calendar cal = Calendar.getInstance();
        if (earliest != null) cal.setTimeInMillis(earliest);
        return context.getString(R.string.analyze_trip_name,
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
    }
}
```

- [ ] **Step 5: 기존 `AnalysisViewModelTest` 를 새 API 로 옮긴다**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisViewModelTest.java` 는 사라진 `setDone(boolean)`/무인자 생성자를 검증하고 있다. 파일을 지우고, 그 파일이 지키던 계약(진행률 계산)은 `AnalysisRendererTest` + 새 `AnalysisPipelineTest` 가 이어받는다:

```bash
cd android && rm app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisViewModelTest.java
```

`AnalysisUiState.progressPercent()` 자체의 순수 계산은 별도로 남긴다 —
`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisUiStateTest.java`:

```java
package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnalysisUiStateTest {

    @Test
    public void percentIsRoundedFromAnalyzedOverTotal() {
        assertEquals(50, new AnalysisUiState(41, 82, false, "a.jpg", 0, 0).progressPercent());
        assertEquals(100, new AnalysisUiState(82, 82, true, "b.jpg", 6, 5).progressPercent());
    }

    @Test
    public void zeroTotalDoesNotDivideByZero() {
        assertEquals(0, new AnalysisUiState(0, 0, false, "", 0, 0).progressPercent());
    }
}
```

- [ ] **Step 6: Fragment 에 시작·취소·전환을 붙인다**

`ui/analysis/AnalysisFragment.java` (전체 교체):

```java
package com.traveltrace.app.ui.analysis;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.map.MapReplayFragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * ANALYZE: 선택 사진의 EXIF 배치 진행 → 완료 → 여행 저장 → MAP.
 *
 * <p>타임존 확인 시트는 S4 소관이라 여기서 띄우지 않는다 — S1 은 EXIF 오프셋이 없으면
 * 기기 타임존으로 폴백한다(plan/06 의 무타임존 폴백).
 */
@AndroidEntryPoint
public class AnalysisFragment extends Fragment {

    private FragmentAnalysisBinding binding;
    private AnalysisViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAnalysisBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(AnalysisViewModel.class);

        binding.analyzeBack.setOnClickListener(v -> {
            vm.cancel();
            NavHostFragment.findNavController(this).popBackStack();
        });

        vm.state().observe(getViewLifecycleOwner(), state ->
                AnalysisRenderer.render(binding, state));

        // 선택 세션이 비어 있으면(프로세스 사망 후 재진입) 분석할 게 없다 — Home 으로.
        vm.abandoned().observe(getViewLifecycleOwner(), abandoned -> {
            if (Boolean.TRUE.equals(abandoned)) {
                NavHostFragment.findNavController(this)
                        .popBackStack(R.id.homeFragment, false);
            }
        });

        vm.savedTripId().observe(getViewLifecycleOwner(), tripId -> {
            if (tripId == null) return;
            binding.gotoMapButton.setOnClickListener(v ->
                    NavHostFragment.findNavController(this).navigate(
                            R.id.action_analysis_to_map,
                            MapReplayFragment.argsFor(tripId)));
        });

        vm.start();
    }

    @Override
    public void onDestroyView() {
        // 화면을 벗어나면 잔여 EXIF 작업을 멈춘다 (plan/03·11: 누수 없이 정리).
        vm.cancel();
        super.onDestroyView();
        binding = null;
    }
}
```

> `MapReplayFragment.argsFor(tripId)` 는 Task 11 에서 만든다. 이 태스크 단독으로는 컴파일되지 않으므로 **Task 11 과 함께 검증**한다 — Step 7 참조.

- [ ] **Step 7: 임시 스텁으로 컴파일을 통과시킨다**

Task 11 이 오기 전까지 `MapReplayFragment` 에 아래 메서드를 추가한다 (Task 11 에서 nav argument 와 함께 정식화된다):

```java
    /** MAP 진입 인자. tripId 하나뿐이라 nav argument 로 나른다(사진 목록은 SelectionSession). */
    public static Bundle argsFor(String tripId) {
        Bundle args = new Bundle();
        args.putString(ARG_TRIP_ID, tripId);
        return args;
    }
```

같은 클래스 상단(`PARIS` 상수 옆)에 추가:

```java
    public static final String ARG_TRIP_ID = "tripId";
```

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisPipelineTest*' --tests '*AnalysisUiStateTest*'`
Expected: PASS (8 tests)

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. `AnalysisScreenshotTest`·`TimezoneSheetScreenshotTest` 는 Renderer 를 직접 호출하므로 골든이 그대로 통과한다.

- [ ] **Step 10: 커밋**

```bash
cd android && git add -A app/src/main/java/com/traveltrace/app/ui/analysis/ \
  app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/traveltrace/app/ui/analysis/
git commit -m "feat: drive ANALYZE from a real EXIF batch and save the trip"
```

---

## Task 10: Stop 에 좌표 붙이기 + 상영모드 라벨을 여행 이름으로

두 개의 UiState 파급 작업을 한 태스크로 묶는다. 둘 다 동작 변경 없는 리팩터이고, 둘 다 `ScreenFixtures` 와 테스트를 함께 고쳐야 한다.

> **파급 규칙 적용:** `Stop` 필드 추가 → ① `ScreenFixtures.map()` 의 생성 6곳, ③ `MapSheetRendererTest`·`TimelineScrubberViewTest` 확인. `cityLabel` 제거 → `MapRenderer`·`MapReplayFragment`·`CinemaOverlayRendererTest`·`CinemaOverlayScreenshotTest` **4곳** 확인.
>
> 상영모드 라벨이 `"파리"`(하드코딩) → `state.tripTitle`("2024 파리 여행") 로 바뀌므로 **`map_cinema.png` 골든 1장은 재기록한다.** 나머지 13장은 불변이어야 한다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayRendererTest.java`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayScreenshotTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `MapUiState.Stop(String id, String name, String time, boolean ai, int extra, int toneColor, double lat, double lng)`; `MapRenderer.renderCinema(ViewCinemaOverlayBinding, MapUiState)` (2-arg)

- [ ] **Step 1: `Stop` 에 좌표를 추가한다**

`ui/map/MapUiState.java` 의 `Stop` 클래스(43–63행)를 교체:

```java
    /** 경로 위 정차 지점 1곳. */
    public static final class Stop {
        public final String id;
        public final String name;
        public final String time;
        /** true 면 AI 근사 위치 (프로토타입 src:'ai') — 배지·점선 표식 대상. */
        public final boolean ai;
        /** 같은 지점의 추가 사진 수 ("+N장"). 0 이면 숨김. */
        public final int extra;
        @ColorInt public final int toneColor;
        /** 지도에 찍을 좌표. PLACED 인 스톱만 여기 오므로 항상 유효하다. */
        public final double lat;
        public final double lng;

        public Stop(String id, String name, String time, boolean ai, int extra,
                    @ColorInt int toneColor, double lat, double lng) {
            this.id = id;
            this.name = name;
            this.time = time;
            this.ai = ai;
            this.extra = extra;
            this.toneColor = toneColor;
            this.lat = lat;
            this.lng = lng;
        }
    }
```

- [ ] **Step 2: `ScreenFixtures.map()` 의 6곳에 실좌표를 준다**

`ui/preview/ScreenFixtures.java` 의 `map()`(98–108행)을 교체:

```java
    public static MapUiState map() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8,
                48.8738, 2.2950));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 4, 0xFFB7C6D6,
                48.8584, 2.2945));
        stops.add(new MapUiState.Stop("seine", "센강 유람선", "13:20", false, 2, 0xFFA9C6DA,
                48.8600, 2.3050));
        stops.add(new MapUiState.Stop("louvre", "루브르 박물관", "15:40", true, 0, 0xFFCDBFA1,
                48.8606, 2.3376));
        stops.add(new MapUiState.Stop("notredame", "노트르담", "16:50", false, 0, 0xFFC3B69B,
                48.8530, 2.3499));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 3, 0xFFD7D0BF,
                48.8867, 2.3431));
        return new MapUiState("2024 파리 여행", 5, stops, 0, false, false, false,
                MapUiState.Speed.NORMAL);
    }
```

같은 파일의 `cityLabel()`(115–118행)을 **삭제한다** — 상영모드가 이제 `tripTitle` 을 쓴다.

- [ ] **Step 3: `renderCinema` 에서 `cityLabel` 파라미터를 없앤다**

`ui/map/MapRenderer.java` 의 `renderCinema` 시그니처(96–97행)를 교체:

```java
    public static void renderCinema(ViewCinemaOverlayBinding binding, MapUiState state) {
```

같은 메서드의 마지막 줄(120행)을 교체:

```java
        // 도시명은 역지오코딩이 필요해 S1 범위 밖이다 — 여행 이름을 쓴다.
        binding.cinemaMeta.setText(ctx.getString(R.string.cinema_meta, stop.time, state.tripTitle));
```

- [ ] **Step 4: 호출부 2곳을 고친다**

`ui/map/MapReplayViewModel.java` 의 `city()` 메서드(31–34행)를 **삭제한다.** `ScreenFixtures` import 는 `unknownThumbTones()` 가 아직 쓰므로 남긴다.

`ui/map/MapReplayFragment.java` 의 140행을 교체:

```java
        MapRenderer.renderCinema(binding.cinemaOverlay, state);
```

- [ ] **Step 5: 시네마 테스트 2개를 새 계약에 맞춘다**

`android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayRendererTest.java` 의 세 테스트 메서드(37–59행)를 교체:

```java
    @Test
    public void hiddenWhenCinemaIsOff() {
        MapRenderer.renderCinema(binding, ScreenFixtures.map());
        assertEquals(View.GONE, binding.cinemaRoot.getVisibility());
    }

    @Test
    public void visibleWithActiveStopNameAndTripTitleMeta() {
        MapRenderer.renderCinema(binding, cinemaAt(0));

        assertEquals(View.VISIBLE, binding.cinemaRoot.getVisibility());
        assertEquals("개선문", binding.cinemaName.getText().toString());
        assertEquals("S1 은 역지오코딩이 없어 도시명 대신 여행 이름을 쓴다",
                "10:12 · 2024 파리 여행", binding.cinemaMeta.getText().toString());
    }

    @Test
    public void followsActiveStop() {
        MapRenderer.renderCinema(binding, cinemaAt(5));

        assertEquals("몽마르트", binding.cinemaName.getText().toString());
        assertEquals("18:30 · 2024 파리 여행", binding.cinemaMeta.getText().toString());
    }
```

`android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayScreenshotTest.java` 의 `renderCinema(...)` 호출에서 세 번째 인자(`ScreenFixtures.cityLabel()`)를 지운다.

- [ ] **Step 6: 컴파일과 나머지 골든 13장을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: **`map_cinema.png` 만 verify 실패**, 나머지는 전부 PASS. 다른 골든이 함께 깨지면 좌표 추가가 렌더에 새어 나간 것이므로 멈추고 원인을 찾는다.

- [ ] **Step 7: 시네마 골든 1장만 재기록한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest -Proborazzi.record=true --tests '*CinemaOverlayScreenshotTest*'`

재기록 후 변경 파일이 `map_cinema.png` 하나뿐인지 확인:

Run: `cd android && git status --porcelain app/src/test/screenshots/`
Expected: ` M app/src/test/screenshots/map_cinema.png` 한 줄만

- [ ] **Step 8: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0, 골든 14장 전부 통과

- [ ] **Step 9: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/map/ \
  app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java \
  app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlay*.java \
  app/src/test/screenshots/map_cinema.png
git commit -m "refactor: give stops coordinates and label cinema mode with the trip title"
```

---

## Task 11: tripId nav 배관 — HOME/ANALYZE → MAP

사진 목록(최대 100개)은 `SelectionSession` 으로 나르지만, **식별자 하나뿐인 tripId 는 nav argument 가 정석**이다 — 프로세스 사망에도 살아남는다.

**Files:**
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelArgsTest.java`

**Interfaces:**
- Consumes: Task 9 `MapReplayFragment.ARG_TRIP_ID`/`argsFor(String)`
- Produces: `MapReplayViewModel(SavedStateHandle, TripRepository)` — `tripId` 를 `SavedStateHandle` 에서 읽는다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelArgsTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.os.Bundle;

import androidx.lifecycle.SavedStateHandle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelArgsTest {

    @Test
    public void argsForPutsTheTripIdUnderTheAgreedKey() {
        Bundle args = MapReplayFragment.argsFor("trip-42");

        assertEquals("trip-42", args.getString(MapReplayFragment.ARG_TRIP_ID));
    }

    @Test
    public void viewModelReadsTheTripIdFromSavedState() {
        SavedStateHandle handle = new SavedStateHandle();
        handle.set(MapReplayFragment.ARG_TRIP_ID, "trip-42");

        assertEquals("trip-42", MapReplayViewModel.tripIdOf(handle));
    }

    @Test
    public void missingTripIdIsNullNotACrash() {
        assertNull("데모 진입(인자 없음)에도 죽지 않아야 한다",
                MapReplayViewModel.tripIdOf(new SavedStateHandle()));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MapReplayViewModelArgsTest*'`
Expected: FAIL — `cannot find symbol: method tripIdOf`

- [ ] **Step 3: nav graph 에 argument 를 선언한다**

`android/app/src/main/res/navigation/nav_graph.xml` 의 `mapReplayFragment`(39–42행)를 교체:

```xml
    <fragment
        android:id="@+id/mapReplayFragment"
        android:name="com.traveltrace.app.ui.map.MapReplayFragment"
        android:label="@string/map_title">
        <!-- 저장 여행 식별자. 없으면(디자인 프리뷰 진입) 픽스처 지도가 뜬다. -->
        <argument
            android:name="tripId"
            app:argType="string"
            app:nullable="true"
            android:defaultValue="@null" />
    </fragment>
```

- [ ] **Step 4: ViewModel 이 `SavedStateHandle` 을 받게 한다**

`ui/map/MapReplayViewModel.java` 의 생성자(20–25행)를 교체:

```java
    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();
    private final SavedStateHandle savedState;

    @Inject
    public MapReplayViewModel(SavedStateHandle savedState) {
        this.savedState = savedState;
        state.setValue(ScreenFixtures.map());
    }

    /** nav argument 로 들어온 저장 여행 식별자. 없으면 null(프리뷰 진입). */
    public static String tripIdOf(SavedStateHandle handle) {
        return handle.get(MapReplayFragment.ARG_TRIP_ID);
    }

    public String tripId() {
        return tripIdOf(savedState);
    }
```

같은 파일 import 에 추가:

```java
import androidx.lifecycle.SavedStateHandle;
```

- [ ] **Step 5: HOME 의 navigate 에 인자를 싣는다**

`ui/home/HomeFragment.java` 의 `onTripClick`(46–53행)을 교체:

```java
    private void onTripClick(HomeUiState.TripCard card) {
        if (card.enabled) {
            NavHostFragment.findNavController(this).navigate(
                    R.id.action_home_to_map,
                    com.traveltrace.app.ui.map.MapReplayFragment.argsFor(card.id));
        } else {
            // 프로토타입 openTripLocked — 제주 카드는 데모에서 열리지 않는다.
            ToastPresenter.show(binding.getRoot(), getString(R.string.home_trip_locked_toast));
        }
    }
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MapReplayViewModelArgsTest*'`
Expected: PASS (3 tests)

- [ ] **Step 7: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0, 골든 14장 통과

- [ ] **Step 8: 커밋**

```bash
cd android && git add app/src/main/res/navigation/nav_graph.xml \
  app/src/main/java/com/traveltrace/app/ui/map/ \
  app/src/main/java/com/traveltrace/app/ui/home/HomeFragment.java \
  app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelArgsTest.java
git commit -m "feat: carry tripId to MAP as a nav argument"
```

---

## Task 12: HOME 실데이터 — 저장 여행 목록 + 첫 사진 hero

`HomeViewModel` 을 `TripRepository` 로 바꾸고, 카드 hero 를 여행 첫 사진 썸네일로 채운다. `heroFor(id)` 의 `"paris"` 리터럴 분기는 실제 여행 ID 가 생기면 전부 제주 이미지가 되므로 폴백으로만 남긴다.

> **파급 규칙 적용:** `TripCard` 필드 추가 → ① `ScreenFixtures.home()` 2곳, ③ `HomeRendererTest` 의 직접 생성 1곳 확인. 픽스처는 `heroPhotoUri` 를 null 로 넘겨 기존 일러스트 경로를 타므로 `home_trips.png`·`home_empty.png`·`home_toast.png` 3장은 불변이다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeUiState.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/home/TripCardAdapter.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeFragment.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/home/HomeRendererTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/home/HomeViewModelTest.java`

**Interfaces:**
- Consumes: Task 3 `TripRepository`/`TripSummary`
- Produces: `HomeUiState.TripCard(String id, String title, String meta, String locationLabel, boolean enabled, Uri heroPhotoUri)`; `HomeViewModel.refresh()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/home/HomeViewModelTest.java`:

```java
package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class HomeViewModelTest {

    private AppExecutors executors;
    private TravelTraceDatabase db;
    private RoomPhotoAnalysisRepository analysisRepo;
    private HomeViewModel vm;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        analysisRepo = new RoomPhotoAnalysisRepository(db, executors);
        vm = new HomeViewModel(ctx, new RoomTripRepository(db, executors));
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    private static PhotoAnalysis placed(long id, String name, long takenAt) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = takenAt;
        a.takenAtHasOffset = true;
        a.lat = 48.85;
        a.lng = 2.29;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private HomeUiState refreshed() {
        vm.refresh();
        ShadowLooper.idleMainLooper();
        return vm.state().getValue();
    }

    @Test
    public void noTripsYieldsTheEmptyState() {
        HomeUiState state = refreshed();

        assertNotNull(state);
        assertTrue(state.empty);
        assertTrue(state.trips.isEmpty());
    }

    @Test
    public void savedTripBecomesAnEnabledCardWithHeroUri() {
        analysisRepo.saveTrip("2024년 6월 여행", "Europe/Paris",
                Arrays.asList(placed(11L, "a.jpg", 1_718_154_720_000L),
                        placed(22L, "b.jpg", 1_718_158_320_000L)),
                tripId -> {});
        ShadowLooper.idleMainLooper();

        HomeUiState state = refreshed();

        assertEquals(1, state.trips.size());
        HomeUiState.TripCard card = state.trips.get(0);
        assertEquals("2024년 6월 여행", card.title);
        assertTrue("실제 저장 여행은 열려야 한다", card.enabled);
        assertNotNull("hero 는 첫 사진의 content URI", card.heroPhotoUri);
        assertTrue(card.heroPhotoUri.toString().endsWith("/11"));
    }

    @Test
    public void cardMetaCarriesPhotoAndDayCounts() {
        analysisRepo.saveTrip("여행", "Asia/Seoul",
                Collections.singletonList(placed(1L, "a.jpg", 1_718_154_720_000L)),
                tripId -> {});
        ShadowLooper.idleMainLooper();

        String meta = refreshed().trips.get(0).meta;

        assertTrue("장수가 들어간다: " + meta, meta.contains("1"));
    }

    @Test
    public void fixtureCardsCarryNoHeroUriSoTheIllustrationPathHolds() {
        assertNull("픽스처는 일러스트 폴백을 타야 골든이 유지된다",
                com.traveltrace.app.ui.preview.ScreenFixtures.home()
                        .trips.get(0).heroPhotoUri);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*HomeViewModelTest*'`
Expected: FAIL — `constructor HomeViewModel in class HomeViewModel cannot be applied to given types`

- [ ] **Step 3: `TripCard` 에 hero URI 를 추가한다**

`ui/home/HomeUiState.java` 의 `TripCard`(28–45행)를 교체:

```java
    /** 여행 카드 1장. heroPhotoUri 가 null 이면 TripCardAdapter 가 id 기반 일러스트로 폴백한다. */
    public static final class TripCard {
        public final String id;
        public final String title;
        public final String meta;
        @Nullable public final String locationLabel;
        /** false 면 탭 시 토스트만 띄운다 (프로토타입 openTripLocked). */
        public final boolean enabled;
        /** 여행의 첫 사진. 픽스처에서는 null. */
        @Nullable public final Uri heroPhotoUri;

        public TripCard(String id, String title, String meta, @Nullable String locationLabel,
                        boolean enabled, @Nullable Uri heroPhotoUri) {
            this.id = id;
            this.title = title;
            this.meta = meta;
            this.locationLabel = locationLabel;
            this.enabled = enabled;
            this.heroPhotoUri = heroPhotoUri;
        }
    }
```

같은 파일 import 에 추가:

```java
import android.net.Uri;
```

- [ ] **Step 4: 픽스처 2곳과 테스트 1곳을 새 생성자에 맞춘다**

`ui/preview/ScreenFixtures.java` 의 `home()`(26–36행)에서 두 `TripCard` 생성에 `null` 을 덧붙인다:

```java
        trips.add(new HomeUiState.TripCard(
                "paris", "2024 파리 여행", "82장 · 4일 · 2024. 6",
                "🇫🇷 파리 · 프랑스", true, null));
        // 제주 카드는 프로토타입에서 시각 전용(openTripLocked → 토스트).
        trips.add(new HomeUiState.TripCard(
                "jeju", "2023 제주 가족여행", "63장 · 3일 · 2023. 10",
                "🌋 제주 · 한국", false, null));
```

`android/app/src/test/java/com/traveltrace/app/ui/home/HomeRendererTest.java` 의 107행 부근 직접 생성을 교체:

```java
        HomeUiState.TripCard noLocation = new HomeUiState.TripCard(
                "paris", "제목", "메타", null, true, null);
```

- [ ] **Step 5: `TripCardAdapter` 에 Glide 경로를 붙인다**

`ui/home/TripCardAdapter.java` 의 `heroFor`(41–44행)와 `onBindViewHolder` 의 hero 줄(57행)을 교체:

```java
    /** heroPhotoUri 가 없을 때만 쓰는 폴백 일러스트(픽스처·썸네일 실패). */
    @DrawableRes
    private static int fallbackHero(String id) {
        return "paris".equals(id) ? R.drawable.hero_trip_paris : R.drawable.hero_trip_jeju;
    }
```

```java
        if (card.heroPhotoUri == null) {
            com.bumptech.glide.Glide.with(holder.b.tripHero).clear(holder.b.tripHero);
            holder.b.tripHero.setImageResource(fallbackHero(card.id));
        } else {
            com.bumptech.glide.Glide.with(holder.b.tripHero)
                    .load(card.heroPhotoUri)
                    .centerCrop()
                    .placeholder(fallbackHero(card.id))
                    .error(fallbackHero(card.id))
                    .into(holder.b.tripHero);
        }
```

같은 파일 클래스 주석(17행)을 교체:

```java
/** HOME 여행 카드 목록. hero 는 첫 사진 썸네일, 없으면 id 기반 일러스트로 폴백한다. */
```

- [ ] **Step 6: 카드 메타 문자열을 리소스로 뺀다**

`android/app/src/main/res/values/strings.xml` 의 HOME 블록(`home_trip_hero_desc` 다음)에 추가:

```xml
    <string name="home_trip_meta">%1$d장 · %2$d일 · %3$s</string>
```

- [ ] **Step 7: `HomeViewModel` 을 Repository 로 바꾼다**

`ui/home/HomeViewModel.java` (전체 교체):

```java
package com.traveltrace.app.ui.home;

import android.content.ContentUris;
import android.content.Context;
import android.provider.MediaStore;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.TripSummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/** HOME 데이터. 저장된 여행 목록을 Room 에서 읽는다 — AI·EXIF 재실행은 없다. */
@HiltViewModel
public class HomeViewModel extends ViewModel {

    private final Context context;
    private final TripRepository tripRepository;
    private final MutableLiveData<HomeUiState> state = new MutableLiveData<>();

    @Inject
    public HomeViewModel(@ApplicationContext Context context, TripRepository tripRepository) {
        this.context = context;
        this.tripRepository = tripRepository;
    }

    public LiveData<HomeUiState> state() {
        return state;
    }

    /** 화면에 돌아올 때마다 호출한다 — 분석 후 새 여행이 바로 보여야 한다. */
    public void refresh() {
        tripRepository.list(summaries -> state.setValue(toState(summaries)));
    }

    private static HomeUiState toState(List<TripSummary> summaries) {
        if (summaries.isEmpty()) {
            return HomeUiState.empty();
        }
        List<HomeUiState.TripCard> cards = new ArrayList<>();
        for (TripSummary s : summaries) {
            cards.add(new HomeUiState.TripCard(
                    s.id,
                    s.name,
                    meta(s),
                    // 위치 라벨은 역지오코딩이 필요해 S1 범위 밖이다 — pill 을 숨긴다.
                    null,
                    true,
                    s.heroMediaStoreId == null ? null : ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, s.heroMediaStoreId)));
        }
        return HomeUiState.trips(cards);
    }

    /** "82장 · 4일 · 2024. 6" (프로토타입 카드 메타). */
    private String meta(TripSummary s) {
        String yearMonth = new SimpleDateFormat("yyyy. M", Locale.KOREA)
                .format(new Date(s.startDateUtc));
        return context.getString(R.string.home_trip_meta,
                s.photoCount, s.dayCount, yearMonth);
    }
}
```

- [ ] **Step 8: Fragment 가 복귀 시 새로고침하게 한다**

`ui/home/HomeFragment.java` 의 `onViewCreated` 마지막(42–43행) 다음에 `onResume` 을 추가:

```java
    @Override
    public void onResume() {
        super.onResume();
        // 분석을 마치고 돌아오면 새 여행이 즉시 보여야 한다.
        vm.refresh();
    }
```

`vm` 을 필드로 승격한다 — `onViewCreated` 의 지역 변수 선언(37행)을 교체:

```java
        vm = new ViewModelProvider(this).get(HomeViewModel.class);
```

클래스 필드에 추가:

```java
    private HomeViewModel vm;
```

- [ ] **Step 9: 테스트가 통과하고 HOME 골든 3장이 그대로인지 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. `home_trips.png`·`home_empty.png`·`home_toast.png` 재기록 없이 통과.

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/home/ \
  app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/traveltrace/app/ui/home/
git commit -m "feat: list saved trips on HOME with first-photo hero thumbnails"
```

---

## Task 13: MAP 실데이터 — 핀 · 경로 · 카메라 fit

마지막 조각. 저장 여행을 열어 실제 지도 위에 마커와 폴리라인을 그리고 전체 경로가 담기도록 카메라를 맞춘다. AI 점선 스타일과 리플레이 재생은 S2·S3 소관이라 여기서 만들지 않는다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRouteRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapRouteRendererTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelLoadTest.java`

**Interfaces:**
- Consumes: Task 3 `TripRepository`/`TripDetail`/`StopRow`, Task 10 `Stop.lat`/`lng`, Task 11 `tripId`
- Produces: `MapRouteRenderer.cameraFor(List<Stop>, int paddingPx)` → `CameraUpdate`, `MapRouteRenderer.draw(GoogleMap, List<Stop>, Context)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

카메라 선택 로직은 `GoogleMap` 없이 검증할 수 있도록 순수 함수로 분리한다.

`android/app/src/test/java/com/traveltrace/app/ui/map/MapRouteRendererTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.android.gms.maps.model.LatLngBounds;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class MapRouteRendererTest {

    private static MapUiState.Stop stop(String id, double lat, double lng) {
        return new MapUiState.Stop(id, id, "10:00", false, 0, 0xFFCCCCCC, lat, lng);
    }

    @Test
    public void boundsCoverEveryStop() {
        List<MapUiState.Stop> stops = Arrays.asList(
                stop("a", 48.8584, 2.2945),
                stop("b", 48.8606, 2.3376));

        LatLngBounds bounds = MapRouteRenderer.boundsOf(stops);

        assertNotNull(bounds);
        assertEquals(48.8584, bounds.southwest.latitude, 0.0001);
        assertEquals(2.3376, bounds.northeast.longitude, 0.0001);
    }

    @Test
    public void singleStopHasNoBoundsSoTheCallerUsesAFixedZoom() {
        assertNull("스톱 1개면 span 이 0이라 bounds fit 이 과도 줌/예외를 낸다",
                MapRouteRenderer.boundsOf(Collections.singletonList(stop("a", 48.85, 2.29))));
    }

    @Test
    public void noStopsHasNoBounds() {
        assertNull(MapRouteRenderer.boundsOf(new ArrayList<>()));
    }

    @Test
    public void identicalCoordinatesAreTreatedAsASinglePoint() {
        List<MapUiState.Stop> sameSpot = Arrays.asList(
                stop("a", 48.85, 2.29),
                stop("b", 48.85, 2.29));

        assertNull("좌표가 전부 같으면 span 이 0 — bounds 대신 고정 줌을 써야 한다",
                MapRouteRenderer.boundsOf(sameSpot));
    }
}
```

`android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelLoadTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.SavedStateHandle;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelLoadTest {

    private AppExecutors executors;
    private TravelTraceDatabase db;
    private RoomTripRepository tripRepo;
    private RoomPhotoAnalysisRepository analysisRepo;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        tripRepo = new RoomTripRepository(db, executors);
        analysisRepo = new RoomPhotoAnalysisRepository(db, executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    private static PhotoAnalysis placed(long id, String name, long takenAt,
                                        double lat, double lng) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = takenAt;
        a.takenAtHasOffset = true;
        a.lat = lat;
        a.lng = lng;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private static PhotoAnalysis unknown(long id, String name) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    private String saveTrip() {
        AtomicReference<String> box = new AtomicReference<>();
        analysisRepo.saveTrip("2024년 6월 여행", "Europe/Paris", Arrays.asList(
                placed(2L, "b.jpg", 1_718_158_320_000L, 48.8606, 2.3376),
                placed(1L, "a.jpg", 1_718_154_720_000L, 48.8584, 2.2945),
                unknown(3L, "c.jpg")), box::set);
        ShadowLooper.idleMainLooper();
        return box.get();
    }

    private MapUiState load(String tripId) {
        SavedStateHandle handle = new SavedStateHandle();
        if (tripId != null) handle.set(MapReplayFragment.ARG_TRIP_ID, tripId);
        MapReplayViewModel vm = new MapReplayViewModel(handle, tripRepo);
        vm.load();
        ShadowLooper.idleMainLooper();
        return vm.state().getValue();
    }

    @Test
    public void savedTripRendersItsStopsInTimeOrder() {
        MapUiState state = load(saveTrip());

        assertEquals("2024년 6월 여행", state.tripTitle);
        assertEquals("PLACED 2장만 스톱이 된다", 2, state.stops.size());
        assertEquals("a.jpg", state.stops.get(0).name);
        assertEquals(48.8584, state.stops.get(0).lat, 0.0001);
        assertEquals("위치 미상 1장", 1, state.unknownCount);
    }

    @Test
    public void stopsCarryFormattedLocalTime() {
        MapUiState state = load(saveTrip());

        assertTrue("시각은 여행 타임존 기준 HH:mm 이어야 한다: " + state.stops.get(0).time,
                state.stops.get(0).time.matches("\\d{2}:\\d{2}"));
    }

    @Test
    public void withoutATripIdTheFixtureMapIsShown() {
        MapUiState state = load(null);

        assertEquals("인자 없이 진입하면 디자인 프리뷰가 뜬다", "2024 파리 여행", state.tripTitle);
        assertEquals(6, state.stops.size());
    }

    @Test
    public void unknownTripIdYieldsAnEmptyRouteWithoutCrashing() {
        MapUiState state = load("does-not-exist");

        assertTrue(state.stops.isEmpty());
        assertEquals(0, state.unknownCount);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MapRouteRendererTest*' --tests '*MapReplayViewModelLoadTest*'`
Expected: FAIL — `cannot find symbol: class MapRouteRenderer`

- [ ] **Step 3: `MapRouteRenderer` 를 만든다**

`ui/map/MapRouteRenderer.java`:

```java
package com.traveltrace.app.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import com.traveltrace.app.R;

import java.util.List;

/**
 * 스톱 목록 → 지도 위 마커·경로·카메라 (PRD §4.4).
 *
 * <p>S1 은 GPS 핀만 그린다. AI 근사 위치의 점선·반투명 구분은 AI 핀이 생기는 S3 부터
 * 의미가 있다(plan/12).
 *
 * <p>좌표 없는 분류는 여기 오기 전에 걸러진다 — PLACED 만 Stop 이 되므로 (0,0) 핀이
 * 생길 여지가 없다.
 */
public final class MapRouteRenderer {

    /** 스톱이 1곳뿐이면 bounds 대신 이 줌으로 맞춘다. */
    public static final float SINGLE_STOP_ZOOM = 15f;

    private MapRouteRenderer() {}

    /**
     * 전체 스톱을 담는 bounds. 스톱이 0·1개이거나 좌표가 전부 같으면 null 을 돌려준다
     * — span 이 0인 bounds 는 과도 줌/예외를 낸다(고전적 함정).
     */
    @Nullable
    public static LatLngBounds boundsOf(List<MapUiState.Stop> stops) {
        if (stops.size() < 2) return null;

        LatLngBounds.Builder builder = LatLngBounds.builder();
        for (MapUiState.Stop stop : stops) {
            builder.include(new LatLng(stop.lat, stop.lng));
        }
        LatLngBounds bounds = builder.build();
        boolean degenerate = bounds.southwest.latitude == bounds.northeast.latitude
                && bounds.southwest.longitude == bounds.northeast.longitude;
        return degenerate ? null : bounds;
    }

    /** bounds 가 없으면 첫 스톱을 고정 줌으로 잡는다. 스톱이 없으면 null. */
    @Nullable
    public static CameraUpdate cameraFor(List<MapUiState.Stop> stops, int paddingPx) {
        LatLngBounds bounds = boundsOf(stops);
        if (bounds != null) {
            return CameraUpdateFactory.newLatLngBounds(bounds, paddingPx);
        }
        if (stops.isEmpty()) return null;
        MapUiState.Stop only = stops.get(0);
        return CameraUpdateFactory.newLatLngZoom(
                new LatLng(only.lat, only.lng), SINGLE_STOP_ZOOM);
    }

    /** 기존 마커·경로를 지우고 다시 그린다. */
    public static void draw(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        map.clear();
        if (stops.isEmpty()) return;

        PolylineOptions route = new PolylineOptions()
                .width(context.getResources().getDimension(R.dimen.map_route_width))
                .color(ContextCompat.getColor(context, R.color.fill_brand));

        BitmapDescriptor pin = pinIcon(context);
        for (MapUiState.Stop stop : stops) {
            LatLng position = new LatLng(stop.lat, stop.lng);
            route.add(position);
            map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(stop.name)
                    .icon(pin));
        }
        // 스톱이 1곳이면 선을 그릴 게 없다.
        if (stops.size() > 1) {
            map.addPolyline(route);
        }
    }

    /** 벡터 드로어블은 BitmapDescriptorFactory 가 직접 못 읽어 비트맵으로 굽는다. */
    private static BitmapDescriptor pinIcon(Context context) {
        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.pin_gps);
        if (drawable == null) {
            return BitmapDescriptorFactory.defaultMarker();
        }
        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }
}
```

`android/app/src/main/res/values/dimens.xml` 에 추가:

```xml
    <dimen name="map_route_width">6dp</dimen>
    <dimen name="map_camera_padding">64dp</dimen>
```

- [ ] **Step 4: `MapReplayViewModel` 이 저장 여행을 읽게 한다**

`ui/map/MapReplayViewModel.java` 의 생성자·`state` 초기화 부분을 교체 (Task 11 에서 만든 `tripIdOf`/`tripId()` 는 유지):

```java
    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();
    private final SavedStateHandle savedState;
    private final TripRepository tripRepository;

    /** Glide 썸네일이 붙기 전 하단시트 배너의 placeholder 톤. */
    private static final int[] TONES = {
            0xFFD9C9A8, 0xFFB7C6D6, 0xFFA9C6DA, 0xFFCDBFA1, 0xFFC3B69B, 0xFFD7D0BF};

    @Inject
    public MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository) {
        this.savedState = savedState;
        this.tripRepository = tripRepository;
    }

    /** tripId 가 있으면 저장 여행을, 없으면 디자인 프리뷰 픽스처를 싣는다. */
    public void load() {
        String tripId = tripId();
        if (tripId == null) {
            state.setValue(ScreenFixtures.map());
            return;
        }
        tripRepository.open(tripId, detail -> {
            if (detail == null) {
                state.setValue(new MapUiState("", 0, new ArrayList<>(), 0,
                        false, false, false, MapUiState.Speed.NORMAL));
                return;
            }
            state.setValue(toState(detail));
        });
    }

    private static MapUiState toState(TripDetail detail) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.KOREA);
        fmt.setTimeZone(TimeZone.getTimeZone(detail.timeZoneId));

        List<MapUiState.Stop> stops = new ArrayList<>();
        for (int i = 0; i < detail.stops.size(); i++) {
            StopRow row = detail.stops.get(i);
            // PLACED 행은 좌표가 있어야 하지만, 그 불변식이 깨졌을 때 0d 로 메우면
            // 정확히 금지된 (0,0) 핀이 생긴다 — 조용히 메우지 말고 걸러내고 로그를 남긴다.
            if (row.lat == null || row.lng == null) {
                Log.w("MapReplayViewModel", "PLACED stop without coordinates: " + row.photoId);
                continue;
            }
            stops.add(new MapUiState.Stop(
                    row.photoId,
                    row.landmarkName != null ? row.landmarkName : row.displayName,
                    row.takenAtUtc == null ? "" : fmt.format(new Date(row.takenAtUtc)),
                    row.source == LocationSource.AI,
                    0,
                    TONES[i % TONES.length],
                    row.lat,
                    row.lng));
        }
        return new MapUiState(detail.name, detail.unknownCount, stops, 0,
                false, false, false, MapUiState.Speed.NORMAL);
    }
```

같은 파일 import 에 추가:

```java
import android.util.Log;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.StopRow;
import com.traveltrace.app.domain.model.TripDetail;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*MapRouteRendererTest*' --tests '*MapReplayViewModelLoadTest*'`
Expected: PASS (8 tests)

- [ ] **Step 6: Fragment 에서 지도를 그린다**

`ui/map/MapReplayFragment.java` 의 `render` 메서드 안, `map.setMapType(...)` 다음(138행)에 추가:

```java
            MapRouteRenderer.draw(map, state.stops, requireContext());
            com.google.android.gms.maps.CameraUpdate camera = MapRouteRenderer.cameraFor(
                    state.stops,
                    getResources().getDimensionPixelSize(R.dimen.map_camera_padding));
            if (camera != null) {
                // 맵뷰 크기가 0인 콜드 스타트에 newLatLngBounds 를 쓰면 SDK 가 던진다 —
                // 레이아웃이 끝난 뒤로 미룬다.
                binding.mapContainer.post(() -> {
                    if (map != null) map.moveCamera(camera);
                });
            }
```

같은 파일 `onMapReady` 의 `moveCamera(...)` 줄(155행)을 교체:

```java
        // 초기 카메라는 여행 스톱에서 결정된다 — 스톱이 없을 때만 파리 고정.
        MapUiState current = vm.state().getValue();
        if (current == null || current.stops.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));
        }
```

`onViewCreated` 의 `vm.state().observe(...)` 줄 바로 앞에 추가:

```java
        vm.load();
```

- [ ] **Step 7: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0, 골든 14장 전부 통과

- [ ] **Step 8: 실기기/에뮬레이터로 왕복을 확인한다**

`local.properties` 에 유효한 `MAPS_API_KEY` 가 없으면 회색 격자만 보인다. 키를 채운 뒤:

Run: `cd android && sh gradlew :app:installDebug`

기기에서 확인할 것:
1. HOME → "새 여행 만들기" → 권한 허용 → 그리드에 **실제 썸네일**이 뜬다
2. 사진 몇 장 제외 → "분석 시작" → 진행률이 **실제로 오르고 파일명이 바뀐다**
3. 완료 → "지도에서 여행 보기" → **핀과 경로선**이 뜨고 전체가 화면에 담긴다
4. 뒤로 → HOME 에 **새 여행 카드**가 첫 사진 썸네일과 함께 뜬다
5. 앱을 완전히 종료 후 재실행 → 카드가 **그대로 남아 있고**, 탭하면 재분석 없이 지도가 뜬다
6. 분석 중 뒤로가기 → 즉시 빠져나오고 여행이 저장되지 않는다

- [ ] **Step 9: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/map/ \
  app/src/main/res/values/dimens.xml \
  app/src/test/java/com/traveltrace/app/ui/map/
git commit -m "feat: draw the saved trip as pins and a route on the real map"
```
