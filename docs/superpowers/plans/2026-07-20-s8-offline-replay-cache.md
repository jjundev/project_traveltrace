# S8 — 저장 여행 오프라인 재생 + 캐시 재사용 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 저장된 여행을 AI·네트워크 호출 0회로 다시 열어 재생하고, 같은 사진의 분석 결과를 (`_ID`+콘텐츠 해시) 캐시로 여행을 넘나들며 재사용하며, 오프라인일 때 "지도 타일은 네트워크가 필요하다"는 한계를 화면에서 정확히 안내한다.

**Architecture:** 세 갈래가 서로 독립적으로 붙는다. ① `analysis_cache` 테이블을 Room v2 가산 마이그레이션으로 추가하고, 콘텐츠 해시를 계산하는 `ContentHasher` 와 그것을 읽고 쓰는 `AnalysisCacheStore` 를 만들어 ANALYZE 배치 루프 앞에 끼운다. ② `AnalysisCostLog` 싱글턴이 vision/geocode 호출과 캐시 hit/miss 를 센다 — "AI 호출 0회"를 주장이 아니라 **어서션 가능한 사실**로 만든다. ③ `ConnectivityMonitor` 가 오프라인을 판별해 `MapUiState.offline` 로 흘리고, MAP 상단바 아래 상시 배너가 뜬다. 경로 폴리라인은 타일이 없어도 그대로 그려진다.

**Tech Stack:** Java 17, Android View/XML, Room(annotationProcessor) + `Migration`, Hilt(annotationProcessor), ExecutorService, `java.security.MessageDigest`, `ConnectivityManager`/`NetworkCapabilities`, Robolectric + Roborazzi.

## Global Constraints

- **Java + View/XML만.** Kotlin·Compose·코루틴·KSP 도입 금지.
- 비동기는 **`ExecutorService`**(`AppExecutors`) 고정. JSON은 Gson. DI/Room은 **annotationProcessor**.
- Gradle은 **Groovy DSL**, buildSrc는 Java.
- `compileSdk 35 / targetSdk 35 / minSdk 33`.
- 사용자 대면 문자열은 전부 **`res/values/strings.xml`의 한국어 리소스**. 코드에 한국어 리터럴 금지(테스트의 어서션 메시지·주석은 예외).
- 좌표 없는 사진을 **절대 (0,0)에 찍지 않는다.** 좌표가 없으면 null 로 둔다 — `0d` 로 coalesce 금지.
- **"오프라인 지도"라고 말하지 않는다.** 지도 타일은 2D·3D 모두 네트워크가 필요하다(PRD §5). UI 카피는 "경로는 표시된다 / 지도 배경은 못 불러온다"로 정확히 쓴다.
- **캐시는 기기 로컬 한정.** `MediaStore _ID` 는 기기 내에서만 안정적이다 — 기기 변경·초기화 후 재분석은 정상 동작이며 README에 명시한다.
- **`MapUiState` 필드를 추가하면 반드시 넷을 함께 확인한다** — ① `ScreenFixtures.map()`, ② `MapReplayViewModel.copy()` 와 빈 상태 생성자, ③ `new MapUiState(` 를 직접 부르는 테스트 9곳/6파일, ④ 하드코딩 문자열을 어서션하는 Renderer 테스트. (S1에서 이 누락으로 Blocker 4건 발생.) **Task 6은 기존 8인자 생성자를 일부러 남겨 이 9곳을 건드리지 않는다** — 손대야 한다면 설계를 잘못 따라간 것이다.
- **기존 단위 테스트 회귀 0건.** 커밋된 골든 스크린샷 **14장은 전부 불변**이고, S8은 신규 골든 1장(`map_offline_banner.png`)만 추가한다.
- 기존 파일을 고치라는 지시의 **줄 번호는 근사치**다. 앞선 태스크가 같은 파일에 줄을 넣으면 밀린다 — 항상 **함께 적힌 코드 내용으로 위치를 찾는다.**
- 전체 테스트는 `cd android && sh gradlew :app:testDebugUnitTest` 로 돌린다(`gradlew` 실행 권한이 없을 수 있어 `sh` 를 앞에 붙인다).

---

## 확정된 설계 결정 (이 계획에서 확정, 문서 역기록은 Task 7)

**1. 콘텐츠 해시 = SHA-256(앞 64KiB ‖ 실제로 읽은 프리픽스 길이 ‖ MediaStore SIZE).**
plan/04-data-layer.md 가 "전체 바이트 vs 앞부분+크기"를 미결로 남긴 자리다. 앞부분+크기를 택한 이유:

- **해시를 먼저 계산 → 캐시 조회 → miss 일 때만 EXIF 파싱**이라는 순서가 가능해진다. 전체 바이트 해시는 EXIF 파싱과 같은 스트림에서 계산해야 중복 I/O 를 피할 수 있는데, 그러면 캐시 조회가 EXIF 파싱 *뒤로* 밀려 "hit 이면 파싱을 건너뛴다"가 원천적으로 불가능해진다.
- 프리픽스는 64KiB 뿐이라 miss 시 스트림을 두 번 열어도 추가 I/O 가 무시할 수준이다. S3 이 붙으면 hit 은 다운스케일·업로드·AI·지오코딩 전부를 건너뛴다.
- JPEG/HEIC 는 EXIF(촬영 시각·서브초·썸네일)가 파일 앞부분에 몰려 있어, **크기까지 같으면서 앞 64KiB 가 동일한 서로 다른 사진**은 실질적으로 존재하지 않는다.

**2. 해시는 원본이 아니라 평범한 `content://` URI 에서 읽는다.**
`MediaStore.setRequireOriginal(uri)` 로 얻는 스트림은 `ACCESS_MEDIA_LOCATION` 승인 여부에 따라 원본이 될 수도, 위치를 지운 파생본이 될 수도 있다. 그 스트림을 해시하면 **권한 상태가 바뀔 때마다 캐시 키가 바뀐다.** 평범한 URI 는 항상 같은 파생본을 주므로 키가 안정적이다. (EXIF 판독은 지금처럼 계속 `setRequireOriginal` 을 쓴다 — 두 경로는 서로 다른 목적으로 서로 다른 URI 를 연다.)

**3. `source == GPS` 인 결과만 캐시한다.**
`UNKNOWN` 은 "위치를 알아내지 못했다"가 아니라 **"아직 AI 를 안 돌렸다"**는 뜻이다(S1 에는 AI 가 없다). 이걸 캐시하면 S3 이 붙었을 때 GPS 없는 사진이 영원히 AI 로 못 가서 제품이 조용히 망가진다. 이 규칙은 호출부가 아니라 `RoomAnalysisCacheStore.put()` 안의 가드로 못박는다.

**4. 오프라인 안내 = 상단바 아래 상시 배너.**
1.9초 토스트는 놓치면 다시 볼 방법이 없고 상영 모드에선 사실상 안 보인다. PRD §5 가 경계하는 "오프라인 지도" 오해를 실제로 막으려면 오프라인인 동안 계속 보여야 한다.

**범위 밖(명시적):** 여행 이름 입력 UX(plan/15 의 "YYYY 도시 여행" 제안)는 S8 이슈 카드에 없다 — 현행 `analyze_trip_name` 자동 명명을 그대로 둔다. 리플레이 애니메이션은 S2, AI·업로드는 S3, 비용 상한 집계는 S4 소관이다.

---

## File Structure

**신규 (`android/app/src/main/java/com/traveltrace/app/`)**

| 경로 | 책임 |
|---|---|
| `data/media/ContentHasher.java` | 캐시 키의 콘텐츠 해시 1개 계산 (앞 64KiB + 크기) |
| `data/db/AnalysisCacheEntity.java` | `analysis_cache` 테이블. 여행과 무관한 (mediaStoreId, contentHash) 복합 PK |
| `data/db/AnalysisCacheDao.java` | `find` / `upsert` / `count` |
| `data/db/Migrations.java` | `MIGRATION_1_2` — `analysis_cache` 가산 생성 |
| `domain/AnalysisCacheStore.java` | 캐시 조회·저장 계약. **io 스레드 전용 동기 API**(콜백 아님) |
| `data/repo/RoomAnalysisCacheStore.java` | Room 구현 + "GPS 결과만 캐시" 가드 |
| `core/AnalysisCostLog.java` | vision·geocode 호출 수, 캐시 hit/miss 카운터 |
| `core/net/ConnectivityMonitor.java` | `isOnline()` — 지도 타일을 받을 수 있는지 |
| `res/layout/view_offline_banner.xml` | MAP 상단바 아래 오프라인 한계 배너 |

**신규 (테스트)**

| 경로 |
|---|
| `test/.../data/media/ContentHasherTest.java` |
| `test/.../data/db/AnalysisCacheDaoTest.java` |
| `test/.../data/db/MigrationTest.java` |
| `test/.../data/repo/RoomAnalysisCacheStoreTest.java` |
| `test/.../core/AnalysisCostLogTest.java` |
| `test/.../ui/analysis/AnalysisCacheReuseTest.java` |
| `test/.../core/net/ConnectivityMonitorTest.java` |
| `test/.../ui/map/MapOfflineBannerRendererTest.java` |
| `test/.../ui/map/MapOfflineBannerScreenshotTest.java` |

**수정**

| 경로 | 변경 |
|---|---|
| `data/media/GalleryImage.java` | `sizeBytes` 필드 추가(해시 키의 절반) |
| `data/media/MediaStoreImageSource.java` | 프로젝션에 `SIZE` 추가, 두 로더가 `sizeBytes` 채움 |
| `domain/model/PhotoAnalysis.java` | `contentHash` 필드 추가 |
| `data/repo/RoomPhotoAnalysisRepository.java` | `PhotoEntity.contentHash` 를 실제로 채움 |
| `data/db/TravelTraceDatabase.java` | v2 + `AnalysisCacheEntity` 등록 |
| `di/DatabaseModule.java` | `.addMigrations(Migrations.MIGRATION_1_2)` |
| `di/AppModule.java` | `AnalysisCacheStore` 바인딩 |
| `data/VisionProviderStub.java`, `data/GeocoderStub.java` | 호출을 `AnalysisCostLog` 에 기록 |
| `ui/analysis/AnalysisViewModel.java` | 해시 → 캐시 조회 → miss 시 EXIF → 캐시 저장 |
| `ui/map/MapUiState.java` | `offline` 필드 + 8인자 생성자 유지 + `withOffline()` |
| `ui/map/MapRenderer.java` | `renderOfflineBanner()` 추가 |
| `ui/map/MapReplayViewModel.java` | `ConnectivityMonitor` 주입, `refreshConnectivity()`, `copy()` 가 `offline` 보존 |
| `ui/map/MapReplayFragment.java` | 배너 렌더 + `onResume()` 재확인 |
| `res/layout/fragment_map_replay.xml` | `offlineBanner` include |
| `res/values/strings.xml` | `map_offline_*` |
| `README.md` *(신규)* | 캐시 기기 로컬 한정 · 오프라인 한계 명시 |
| `plan/04-data-layer.md`, `plan/15-trip-persistence-offline.md`, `plan/ISSUES.md`, `plan/00-overview.md` | 결정 역기록 · 상태 갱신 |
| 기존 테스트: `AnalysisPipelineTest`, `MapReplayViewModel*Test`(3), `MediaStoreImageSourceTest`, `ExifExtractorTest` | 생성자 시그니처 추종 |

---

## Task 1: 콘텐츠 해시 — `GalleryImage.sizeBytes` + `ContentHasher`

캐시 키의 절반인 콘텐츠 해시를 계산한다. 파일 크기는 MediaStore 커서에서 공짜로 얻어 파일을 두 번 열지 않는다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/media/ContentHasher.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/media/GalleryImage.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/media/MediaStoreImageSource.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/media/ContentHasherTest.java`

**Interfaces:**
- Consumes: 없음 (최하위)
- Produces:
  - `GalleryImage(long id, Uri contentUri, String displayName, Long dateTakenUtc, long sizeBytes)` — 공개 final 필드 `sizeBytes`
  - `ContentHasher.hash(Uri uri, long sizeBytes)` → `@Nullable String` (64자 소문자 hex, 실패 시 null)
  - `ContentHasher.PREFIX_BYTES` = `65536`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/media/ContentHasherTest.java`:

```java
package com.traveltrace.app.data.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.ByteArrayInputStream;

@RunWith(RobolectricTestRunner.class)
public class ContentHasherTest {

    private Context ctx;
    private ContentHasher hasher;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        hasher = new ContentHasher(ctx);
    }

    /**
     * 같은 Uri 를 여러 번 열어야 하는 테스트가 있으므로 registerInputStream(단일 인스턴스,
     * 한 번 소비하면 고갈)이 아니라 registerInputStreamSupplier 를 쓴다 — 매 open 마다
     * 새 스트림을 만들어 실기기의 ContentProvider 와 같은 모양이 된다.
     */
    private Uri register(String name, byte[] bytes) {
        Uri uri = Uri.parse("content://media/external/images/media/" + Math.abs(name.hashCode()));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes));
        return uri;
    }

    private static byte[] bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) ((i * 31 + seed) & 0xFF);
        return b;
    }

    @Test
    public void sameContentAndSizeHashToTheSameKey() {
        byte[] content = bytes(4096, 7);
        String first = hasher.hash(register("a.jpg", content), content.length);
        String second = hasher.hash(register("b.jpg", content), content.length);

        assertEquals("같은 바이트·같은 크기는 같은 캐시 키여야 한다 — 여행을 넘나드는 hit 의 근거",
                first, second);
    }

    @Test
    public void hashIsLowercaseHexSha256() {
        byte[] content = bytes(1024, 1);
        String hash = hasher.hash(register("hex.jpg", content), content.length);

        assertEquals("SHA-256 은 32바이트 = hex 64자", 64, hash.length());
        assertTrue("소문자 hex 만 나와야 한다: " + hash, hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void differentContentHashesDifferently() {
        byte[] one = bytes(2048, 1);
        byte[] two = bytes(2048, 2);

        assertNotEquals(hasher.hash(register("one.jpg", one), one.length),
                hasher.hash(register("two.jpg", two), two.length));
    }

    @Test
    public void sizeIsPartOfTheKeyEvenWhenThePrefixMatches() {
        // 프리픽스(64KiB)가 완전히 동일하고 뒤쪽만 다른 두 파일 — 크기가 키에 섞이지
        // 않으면 서로 충돌해서 남의 분석 결과를 재사용하게 된다.
        byte[] shared = bytes(ContentHasher.PREFIX_BYTES, 3);
        Uri uri = register("tail.jpg", shared);

        assertNotEquals("프리픽스가 같아도 파일 크기가 다르면 다른 키여야 한다",
                hasher.hash(uri, shared.length),
                hasher.hash(uri, shared.length + 1_000_000L));
    }

    @Test
    public void onlyThePrefixIsRead() {
        // 프리픽스보다 큰 파일이라도 앞 64KiB 만 읽어야 한다 — 뒤쪽이 달라도 (크기가 같다면)
        // 같은 키가 나오는 것이 이 설계의 의도된 트레이드오프다.
        byte[] head = bytes(ContentHasher.PREFIX_BYTES, 5);

        byte[] longA = new byte[ContentHasher.PREFIX_BYTES + 512];
        byte[] longB = new byte[ContentHasher.PREFIX_BYTES + 512];
        System.arraycopy(head, 0, longA, 0, head.length);
        System.arraycopy(head, 0, longB, 0, head.length);
        longB[longB.length - 1] = 0x7F;

        assertEquals("앞 64KiB + 크기가 같으면 같은 키 — 전체 바이트를 읽지 않는다는 증거",
                hasher.hash(register("longA.jpg", longA), longA.length),
                hasher.hash(register("longB.jpg", longB), longB.length));
    }

    @Test
    public void unreadableUriYieldsNullInsteadOfThrowing() {
        Uri missing = Uri.parse("content://com.traveltrace.absent/999");

        assertNull("읽을 수 없으면 null — 호출부는 캐시를 건너뛰고 정상 분석을 계속한다",
                hasher.hash(missing, 123L));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ContentHasherTest*'`
Expected: FAIL — `error: cannot find symbol: class ContentHasher`

- [ ] **Step 3: `ContentHasher` 를 만든다**

`android/app/src/main/java/com/traveltrace/app/data/media/ContentHasher.java`:

```java
package com.traveltrace.app.data.media;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 캐시 키의 콘텐츠 해시 (PRD §4.7, plan/04 "(_ID + 콘텐츠 해시)").
 *
 * <p><b>앞 64KiB + 크기</b>를 해시한다(전체 바이트가 아니다). 그래야 "해시 → 캐시 조회 →
 * miss 일 때만 EXIF 파싱" 순서가 가능하다 — 전체 바이트 해시는 EXIF 파싱과 같은 스트림에서
 * 계산해야 중복 I/O 를 피하므로 캐시 조회가 파싱 뒤로 밀려 hit 이어도 파싱을 못 건너뛴다.
 * JPEG/HEIC 는 EXIF(촬영 시각·서브초·썸네일)가 앞부분에 몰려 있어, 크기까지 같으면서 앞
 * 64KiB 가 동일한 서로 다른 사진은 실질적으로 존재하지 않는다.
 *
 * <p><b>{@link android.provider.MediaStore#setRequireOriginal} 을 쓰지 않는다.</b> 그 URI 가
 * 주는 스트림은 ACCESS_MEDIA_LOCATION 승인 여부에 따라 원본이 되기도 위치를 지운 파생본이
 * 되기도 해서, 권한 상태가 바뀌면 캐시 키가 통째로 바뀐다. 평범한 URI 는 항상 같은 파생본을
 * 주므로 키가 안정적이다 — EXIF 판독(ExifExtractor)만 원본 경로를 쓴다.
 *
 * <p>키는 기기 로컬 한정이다: 짝인 {@code mediaStoreId} 가 기기 내에서만 안정적이므로
 * 기기 변경·초기화 후에는 캐시가 통째로 miss 된다(정상, README 에 명시).
 */
@Singleton
public class ContentHasher {

    private static final String TAG = "ContentHasher";

    /** 해시에 넣을 파일 앞부분 크기. */
    public static final int PREFIX_BYTES = 64 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Context context;

    @Inject
    public ContentHasher(@ApplicationContext Context context) {
        this.context = context;
    }

    /**
     * @param sizeBytes MediaStore 가 아는 파일 크기. 커서에서 이미 읽어 오므로 파일을 한 번 더
     *                  열 필요가 없다. 모르면 0 을 넘겨도 되지만 그만큼 충돌 여지가 커진다.
     * @return 소문자 hex 64자, 또는 읽지 못했을 때 null(호출부는 캐시를 건너뛴다).
     */
    @Nullable
    @WorkerThread
    public String hash(Uri uri, long sizeBytes) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int remaining = PREFIX_BYTES;
            long consumed = 0;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                digest.update(buffer, 0, read);
                remaining -= read;
                consumed += read;
            }

            // 프리픽스 길이와 전체 크기를 섞는다 — 앞부분이 같고 뒤쪽만 다른 파일이
            // 서로의 분석 결과를 재사용하는 것을 크기가 막는다.
            digest.update(longBytes(consumed));
            digest.update(longBytes(sizeBytes));
            return hex(digest.digest());
        } catch (IOException | SecurityException | RuntimeException unreadable) {
            // 사진이 지워졌거나 권한이 빠진 흔한 경우다 — 던지면 배치 전체가 멈춘다.
            Log.w(TAG, "hash failed for " + uri, unreadable);
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 은 모든 JVM 이 제공한다", impossible);
        }
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ContentHasherTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: `GalleryImage` 에 `sizeBytes` 를 추가한다**

`android/app/src/main/java/com/traveltrace/app/data/media/GalleryImage.java` (전체 교체):

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

    /**
     * 파일 크기(바이트). 캐시 키의 콘텐츠 해시에 섞인다 — 커서에서 이미 읽어 오므로
     * 파일을 한 번 더 열지 않아도 된다(plan/04 "앞부분+크기" 결정). 모르면 0.
     */
    public final long sizeBytes;

    public GalleryImage(long id, Uri contentUri, String displayName,
                        @Nullable Long dateTakenUtc, long sizeBytes) {
        this.id = id;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.dateTakenUtc = dateTakenUtc;
        this.sizeBytes = sizeBytes;
    }
}
```

- [ ] **Step 6: `MediaStoreImageSource` 가 크기를 읽게 한다**

`android/app/src/main/java/com/traveltrace/app/data/media/MediaStoreImageSource.java` 를 열고 세 곳을 고친다.

① `PROJECTION` 배열에 `SIZE` 를 추가한다 — 기존 3개 뒤에 붙인다:

```java
    private static final String[] PROJECTION = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
    };
```

② 커서에서 행을 `GalleryImage` 로 만드는 지점(`new GalleryImage(` 를 grep 해서 찾는다)에서 `SIZE` 컬럼을 읽어 5번째 인자로 넘긴다. 컬럼 인덱스를 이미 `getColumnIndexOrThrow` 로 잡고 있으면 같은 방식으로 하나 더 잡는다:

```java
        int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
        ...
        images.add(new GalleryImage(id, contentUri, displayName, dateTaken,
                cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol)));
```

③ 두 로더(`loadRecent`, `loadByIds`)가 같은 커서 읽기 헬퍼를 공유하지 않고 각각 행을 만들고 있다면 **양쪽 모두** 고친다.

- [ ] **Step 7: `new GalleryImage(` 호출부를 전부 맞춘다**

Run: `cd android && grep -rn "new GalleryImage(" app/src`

나오는 모든 호출부(프로덕션 + `ExifExtractorTest.image()` 헬퍼 + `MediaStoreImageSourceTest`)에 5번째 인자를 넣는다. 테스트 헬퍼는 크기가 검증 대상이 아니므로 `0L` 로 둔다:

```java
    private static GalleryImage image(Uri uri, long id, String name) {
        return new GalleryImage(id, uri, name, null, 0L);
    }
```

- [ ] **Step 8: `RoboCursor` 를 세우는 자리를 *전부* 찾아 SIZE 컬럼을 더한다**

`MediaStoreImageSource` 의 커서 읽기는 한 곳에 모여 있어서, `getColumnIndexOrThrow(SIZE)` 를 추가하는 순간 **그 코드를 타는 모든 Robolectric 커서**가 SIZE 컬럼을 갖고 있어야 한다. 없으면 테스트 실패가 아니라 `IllegalArgumentException` 이 난다. 예시 하나만 고치면 반드시 빠뜨리므로 grep 으로 전수한다:

Run: `cd android && grep -rn "setColumnNames" app/src/test`

**컬럼 목록과 행 데이터가 같은 자리에 있지 않다.** 두 파일의 `seed()` 는 `private void seed(Object[]... rows)` 가변인자 헬퍼라, 컬럼 이름은 헬퍼 안에 있고 **행 리터럴은 각 호출부에 흩어져 있다.** 컬럼만 4개로 늘리고 행을 3칸으로 두면 `RoboCursor` 가 `results[row][3]` 를 무보호로 인덱싱해 `ArrayIndexOutOfBoundsException` 이 난다(`getColumnIndexOrThrow` 예외가 아니다). 그래서 **양쪽을 함께** 고친다.

2026-07-20 기준 고칠 자리는 다음과 같다:

| 파일 | 컬럼 목록 | 행 리터럴(각각 끝에 크기 한 칸 추가) |
|---|---|---|
| `data/media/MediaStoreImageSourceTest.java` | `seed()` 헬퍼 (~61행) | `seed(...)` 호출부 — 약 107, 122, 130~132행. 인자 없는 `seed()`(~139행)는 행이 없으므로 그대로 |
| `data/media/MediaStoreImageSourceTest.java` | `loadByIdsFiltersToOnlyTheRequestedIdsViaSqlSelection()` 안 익명 `ContentProvider.query()` 가 만드는 **두 번째** 커서 (~205행) | 같은 블록 안의 `setResults` |
| `ui/photo/PhotoSelectionViewModelTest.java` | `seed()` 헬퍼 (~58행) | `seed(...)` 호출부 — 약 40, 174, 203행. 인자 없는 `seed()`(~146행)는 그대로 |
| `ui/analysis/AnalysisPipelineTest.java` | `seedGallery()` | Task 5 Step 6 에서 함께 처리하므로 여기선 건너뛴다 |

컬럼 목록 쪽:

```java
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE));
```

행 리터럴 쪽 — 기존 값은 손대지 말고 **끝에 한 칸만 덧붙인다**(기존 어서션이 앞 세 값에 걸려 있다):

```java
        seed(new Object[]{11L, "a.jpg", 1_700_000_000_000L, 2048L});
```

`grep -n "seed(\|setResults" app/src/test/java/com/traveltrace/app/data/media/MediaStoreImageSourceTest.java app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModelTest.java` 로 빠진 자리가 없는지 최종 확인한다.

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0.

두 가지 실패가 Step 8 누락의 신호다: `IllegalArgumentException`(컬럼 목록에 SIZE 를 안 넣은 커서가 있다) 또는 `ArrayIndexOutOfBoundsException`(컬럼은 넣었는데 행 리터럴에 크기 칸을 안 넣었다). 후자가 더 흔하다 — 행은 호출부마다 흩어져 있기 때문이다.

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/data/media/ \
  app/src/test/java/com/traveltrace/app/data/media/ \
  app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModelTest.java \
  app/src/test/java/com/traveltrace/app/data/exif/ExifExtractorTest.java
git commit -m "feat: hash photo content (first 64KiB + size) for the analysis cache key"
```

---

## Task 2: `analysis_cache` 테이블 · Room v2 가산 마이그레이션

`TravelTraceDatabase` 자바독이 "AnalysisCache 는 S4/S8 에서 가산 마이그레이션으로 들어온다"고 약속해 둔 자리를 채운다. 캐시는 **여행과 독립**이므로 여행을 지워도 남는다 — 그게 "다른 여행에서도 hit" 의 전제다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/db/AnalysisCacheEntity.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/db/AnalysisCacheDao.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/db/Migrations.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/db/TravelTraceDatabase.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/DatabaseModule.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/db/AnalysisCacheDaoTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/db/MigrationTest.java`
- Commit: `android/app/schemas/com.traveltrace.app.data.db.TravelTraceDatabase/2.json` (빌드가 생성)

**Interfaces:**
- Consumes: Task 1 없음 (독립)
- Produces:
  - `AnalysisCacheEntity` 공개 필드: `mediaStoreId`(long), `contentHash`(String), `lat`/`lng`(Double), `source`(LocationSource), `landmarkName`/`city`/`country`(String), `classification`(LocationClassification), `confidence`(Double), `takenAtUtc`(Long), `takenAtHasOffset`(boolean), `model`(String), `createdAt`(long)
  - `AnalysisCacheDao.find(long mediaStoreId, String contentHash)` → `@Nullable AnalysisCacheEntity`
  - `AnalysisCacheDao.upsert(AnalysisCacheEntity)` → `void`
  - `AnalysisCacheDao.count()` → `int`
  - `TravelTraceDatabase.analysisCacheDao()`, `TravelTraceDatabase.VERSION` = `2`
  - `Migrations.MIGRATION_1_2` → `androidx.room.migration.Migration`

- [ ] **Step 1: 실패하는 테스트 2개를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/db/AnalysisCacheDaoTest.java`:

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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnalysisCacheDaoTest {

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

    private static AnalysisCacheEntity entry(long mediaStoreId, String hash, double lat) {
        AnalysisCacheEntity e = new AnalysisCacheEntity();
        e.mediaStoreId = mediaStoreId;
        e.contentHash = hash;
        e.lat = lat;
        e.lng = 2.2945;
        e.source = LocationSource.GPS;
        e.classification = LocationClassification.PLACED;
        e.takenAtUtc = 1_718_154_720_000L;
        e.takenAtHasOffset = true;
        e.createdAt = 1_718_200_000_000L;
        return e;
    }

    @Test
    public void entryRoundTripsByIdAndHash() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        AnalysisCacheEntity found = db.analysisCacheDao().find(11L, "hash-a");

        assertNotNull(found);
        assertEquals(48.8584, found.lat, 0.0001);
        assertEquals(LocationSource.GPS, found.source);
        assertEquals(LocationClassification.PLACED, found.classification);
        assertEquals(Long.valueOf(1_718_154_720_000L), found.takenAtUtc);
    }

    @Test
    public void aDifferentHashOnTheSameIdIsAMiss() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        assertNull("사진이 편집되어 내용이 바뀌면 옛 결과를 재사용하면 안 된다",
                db.analysisCacheDao().find(11L, "hash-b"));
    }

    @Test
    public void theSameIdAndHashUpsertsInsteadOfDuplicating() {
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 37.5665));

        assertEquals("(_ID+해시) 는 복합 PK 다 — 행이 늘어나면 안 된다", 1,
                db.analysisCacheDao().count());
        assertEquals(37.5665, db.analysisCacheDao().find(11L, "hash-a").lat, 0.0001);
    }

    @Test
    public void cacheSurvivesTripDeletionBecauseItIsTripIndependent() {
        TripEntity trip = new TripEntity();
        trip.id = "t1";
        trip.name = "지울 여행";
        trip.timeZoneId = "Asia/Seoul";
        trip.createdAt = 1L;
        db.tripDao().insert(trip);
        db.analysisCacheDao().upsert(entry(11L, "hash-a", 48.8584));

        db.tripDao().deleteById("t1");

        assertNotNull("여행을 지워도 캐시는 남아야 한다 — 다음 여행이 그걸 재사용한다",
                db.analysisCacheDao().find(11L, "hash-a"));
    }
}
```

`android/app/src/test/java/com/traveltrace/app/data/db/MigrationTest.java`:

```java
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
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCacheDaoTest*' --tests '*MigrationTest*'`
Expected: FAIL — `error: cannot find symbol: class AnalysisCacheEntity`

- [ ] **Step 3: 엔티티를 만든다**

`android/app/src/main/java/com/traveltrace/app/data/db/AnalysisCacheEntity.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/**
 * plan/04-data-layer.md 의 AnalysisCache. 키 = (MediaStore _ID + 콘텐츠 해시).
 *
 * <p><b>여행과 독립이다</b> — trips 로의 FK 가 일부러 없다. 여행을 지워도 이 행은 남아야
 * 다음 여행이 같은 사진을 다시 분석하지 않는다(PRD §4.7 "다른 여행에서도 hit").
 *
 * <p>휘발성 {@code content://} URI 를 키로 쓰지 않는 이유는 plan/04 참고 — 재부팅·재설치
 * 후에도 안정적인 _ID 와, 사진이 편집되면 바뀌는 콘텐츠 해시의 조합이라야 "같은 사진"을
 * 정확히 뜻한다.
 */
@Entity(tableName = "analysis_cache", primaryKeys = {"mediaStoreId", "contentHash"})
public class AnalysisCacheEntity {

    public long mediaStoreId;

    @NonNull
    public String contentHash = "";

    /** 좌표가 없으면 null. 절대 0.0 으로 채우지 않는다 — (0,0) 핀 방지. */
    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    @NonNull
    public LocationSource source = LocationSource.NONE;

    @Nullable
    public String landmarkName;

    @Nullable
    public String city;

    @Nullable
    public String country;

    @NonNull
    public LocationClassification classification = LocationClassification.UNKNOWN;

    @Nullable
    public Double confidence;

    /** 촬영 시각(UTC millis). 같은 바이트에서 파생되므로 캐시가 함께 들고 있는다. */
    @Nullable
    public Long takenAtUtc;

    public boolean takenAtHasOffset;

    /** 결과를 만든 vision 모델 ID. S1/S8 의 GPS 결과는 모델이 없어 null. S5 가 채운다. */
    @Nullable
    public String model;

    public long createdAt;
}
```

- [ ] **Step 4: DAO 를 만든다**

`android/app/src/main/java/com/traveltrace/app/data/db/AnalysisCacheDao.java`:

```java
package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface AnalysisCacheDao {

    /** hit 이면 AI·지오코딩·EXIF 재파싱을 전부 건너뛴다(PRD §4.7). */
    @Nullable
    @Query("SELECT * FROM analysis_cache WHERE mediaStoreId = :mediaStoreId "
            + "AND contentHash = :contentHash")
    AnalysisCacheEntity find(long mediaStoreId, String contentHash);

    /** 복합 PK 라 같은 (_ID+해시) 는 행이 늘지 않고 덮어써진다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AnalysisCacheEntity entry);

    @Query("SELECT COUNT(*) FROM analysis_cache")
    int count();
}
```

- [ ] **Step 5: 마이그레이션을 만든다**

`android/app/src/main/java/com/traveltrace/app/data/db/Migrations.java`:

```java
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
}
```

- [ ] **Step 6: 데이터베이스를 v2 로 올린다**

`android/app/src/main/java/com/traveltrace/app/data/db/TravelTraceDatabase.java` (전체 교체):

```java
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
```

- [ ] **Step 7: DI 가 마이그레이션을 쓰게 한다**

`android/app/src/main/java/com/traveltrace/app/di/DatabaseModule.java` — `provideDatabase` 메서드와 그 위 자바독을 통째로 교체한다:

```java
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
```

같은 파일 import 에 `com.traveltrace.app.data.db.Migrations;` 를 추가한다.

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCacheDaoTest*' --tests '*MigrationTest*'`
Expected: PASS (6 tests)

`MigrationTest` 가 "Migration didn't properly handle" 로 실패하면 `createV1()` 의 CREATE 문이 `app/schemas/com.traveltrace.app.data.db.TravelTraceDatabase/1.json` 의 `createSql` 과 다른 것이다 — 그 파일에서 그대로 복사해 맞춘다.

- [ ] **Step 9: 새 스키마 파일이 생성됐는지 확인한다**

Run: `cd android && ls app/schemas/com.traveltrace.app.data.db.TravelTraceDatabase/`
Expected: `1.json` 과 `2.json` 이 모두 보인다. `2.json` 이 없으면 `sh gradlew :app:compileDebugJavaWithJavac` 를 한 번 돌린다.

- [ ] **Step 10: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0

- [ ] **Step 11: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/data/db/ \
  app/src/main/java/com/traveltrace/app/di/DatabaseModule.java \
  app/src/test/java/com/traveltrace/app/data/db/ \
  app/schemas/
git commit -m "feat: add the trip-independent analysis_cache table via an additive v2 migration"
```

---

## Task 3: `AnalysisCacheStore` — 도메인 계약 + Room 구현

DAO 를 그대로 노출하면 호출부가 "무엇을 캐시해도 되는가"를 각자 판단하게 된다. 그 규칙(**GPS 결과만**)을 구현 안으로 밀어넣어 호출부가 틀릴 수 없게 만든다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/domain/AnalysisCacheStore.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/repo/RoomAnalysisCacheStore.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/domain/model/PhotoAnalysis.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/repo/RoomPhotoAnalysisRepository.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/AppModule.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/repo/RoomAnalysisCacheStoreTest.java`

**Interfaces:**
- Consumes: Task 2 `TravelTraceDatabase.analysisCacheDao()`, `AnalysisCacheEntity`
- Produces:
  - `PhotoAnalysis.contentHash` (`@Nullable String` 공개 필드)
  - `AnalysisCacheStore.get(long mediaStoreId, String contentHash)` → `@Nullable PhotoAnalysis` (**io 스레드에서 동기 호출**)
  - `AnalysisCacheStore.put(PhotoAnalysis analysis)` → `void` (**io 스레드에서 동기 호출**, 캐시 불가 결과는 조용히 무시)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/data/repo/RoomAnalysisCacheStoreTest.java`:

```java
package com.traveltrace.app.data.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RoomAnalysisCacheStoreTest {

    private TravelTraceDatabase db;
    private RoomAnalysisCacheStore store;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        store = new RoomAnalysisCacheStore(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static PhotoAnalysis gps(long id, String hash) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = "a.jpg";
        a.contentHash = hash;
        a.takenAtUtc = 1_718_154_720_000L;
        a.takenAtHasOffset = true;
        a.lat = 48.8584;
        a.lng = 2.2945;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    private static PhotoAnalysis unknown(long id, String hash) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = "b.jpg";
        a.contentHash = hash;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    @Test
    public void storedGpsResultComesBackWithItsCoordinatesAndTime() {
        store.put(gps(11L, "hash-a"));

        PhotoAnalysis hit = store.get(11L, "hash-a");

        assertNotNull(hit);
        assertEquals(48.8584, hit.lat, 0.0001);
        assertEquals(2.2945, hit.lng, 0.0001);
        assertEquals(LocationSource.GPS, hit.source);
        assertEquals(LocationClassification.PLACED, hit.classification);
        assertEquals(Long.valueOf(1_718_154_720_000L), hit.takenAtUtc);
        assertEquals("키의 절반은 되돌려줘야 저장 경로가 그대로 이어 쓸 수 있다",
                "hash-a", hit.contentHash);
    }

    @Test
    public void aMissReturnsNull() {
        assertNull(store.get(11L, "never-seen"));
    }

    @Test
    public void unknownResultsAreNotCached() {
        store.put(unknown(12L, "hash-b"));

        assertNull("UNKNOWN 은 '위치가 없다'가 아니라 '아직 AI 를 안 돌렸다'는 뜻이다 — "
                        + "캐시하면 S3 이 붙어도 이 사진은 영원히 AI 로 못 간다",
                store.get(12L, "hash-b"));
    }

    @Test
    public void aResultWithoutAHashIsNotCached() {
        PhotoAnalysis noHash = gps(13L, null);

        store.put(noHash);

        assertEquals("해시를 못 구한 사진은 키가 없다 — 조용히 건너뛴다", 0,
                db.analysisCacheDao().count());
    }

    @Test
    public void theSamePhotoHitsFromADifferentTripBecauseTheCacheIsTripIndependent() {
        // 여행 A 의 분석 결과를 캐시에 넣고, 여행 B 가 같은 사진(같은 _ID+해시)을 조회한다.
        // 캐시가 여행을 키에 넣지 않는다는 사실 자체가 이 테스트의 대상이다.
        store.put(gps(11L, "hash-a"));

        PhotoAnalysis fromAnotherTrip = store.get(11L, "hash-a");

        assertNotNull("여행이 달라도 같은 사진이면 hit 이어야 한다 (PRD §4.7)", fromAnotherTrip);
        assertEquals(11L, fromAnotherTrip.mediaStoreId);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*RoomAnalysisCacheStoreTest*'`
Expected: FAIL — `error: cannot find symbol: class RoomAnalysisCacheStore`

- [ ] **Step 3: `PhotoAnalysis` 에 `contentHash` 를 추가한다**

`android/app/src/main/java/com/traveltrace/app/domain/model/PhotoAnalysis.java` — `public String displayName;` 바로 아래에 추가:

```java
    /**
     * 캐시 키의 절반 (짝은 mediaStoreId). 스트림을 못 읽었으면 null 이고, 그때는 캐시를
     * 통째로 건너뛴다.
     */
    @Nullable
    public String contentHash;
```

- [ ] **Step 4: 도메인 계약을 만든다**

`android/app/src/main/java/com/traveltrace/app/domain/AnalysisCacheStore.java`:

```java
package com.traveltrace.app.domain;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.traveltrace.app.domain.model.PhotoAnalysis;

/**
 * (MediaStore _ID + 콘텐츠 해시) 캐시 (PRD §4.7). 여행과 독립이라 같은 사진은 여행을
 * 넘나들며 hit 된다.
 *
 * <p><b>이 인터페이스만 {@link Callback} 규약을 따르지 않고 동기다.</b> 유일한 호출부인
 * 분석 배치 루프가 이미 {@code AppExecutors.io()} 위에서 사진을 한 장씩 순회하고 있어서,
 * 조회 결과가 있어야 다음 줄(EXIF 를 읽을지 말지)을 정할 수 있기 때문이다. 콜백으로 만들면
 * 그 루프를 콜백 사슬로 뒤집어야 하는데 얻는 게 없다. 대신 두 메서드 모두
 * {@link WorkerThread} 로 못박는다 — 메인스레드에서 부르면 Room 이 던진다.
 */
public interface AnalysisCacheStore {

    /** hit 이면 저장돼 있던 결과를, miss 면 null 을 돌려준다. */
    @Nullable
    @WorkerThread
    PhotoAnalysis get(long mediaStoreId, String contentHash);

    /**
     * 결과를 캐시한다. <b>캐시해도 되는 결과인지는 구현이 판단한다</b> — 호출부는 매번
     * 불러도 되고, 캐시 불가(해시 없음·아직 AI 를 안 돌린 UNKNOWN)면 조용히 무시된다.
     */
    @WorkerThread
    void put(PhotoAnalysis analysis);
}
```

- [ ] **Step 5: Room 구현을 만든다**

`android/app/src/main/java/com/traveltrace/app/data/repo/RoomAnalysisCacheStore.java`:

```java
package com.traveltrace.app.data.repo;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.AnalysisCacheEntity;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.AnalysisCacheStore;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 호출부가 이미 io 스레드에 있으므로 AppExecutors 를 거치지 않고 곧장 Room 을 친다
 * (이유는 {@link AnalysisCacheStore} 자바독).
 */
@Singleton
public class RoomAnalysisCacheStore implements AnalysisCacheStore {

    private final TravelTraceDatabase db;

    @Inject
    public RoomAnalysisCacheStore(TravelTraceDatabase db) {
        this.db = db;
    }

    @Nullable
    @Override
    public PhotoAnalysis get(long mediaStoreId, String contentHash) {
        if (contentHash == null) return null;
        AnalysisCacheEntity entry = db.analysisCacheDao().find(mediaStoreId, contentHash);
        if (entry == null) return null;

        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = entry.mediaStoreId;
        a.contentHash = entry.contentHash;
        a.takenAtUtc = entry.takenAtUtc;
        a.takenAtHasOffset = entry.takenAtHasOffset;
        a.lat = entry.lat;
        a.lng = entry.lng;
        a.source = entry.source;
        a.classification = entry.classification;
        // displayName 은 일부러 채우지 않는다 — 파일명은 콘텐츠가 아니라 MediaStore 행의
        // 속성이라 사용자가 이름을 바꾸면 캐시된 값이 낡는다. 호출부가 살아 있는 커서
        // 값으로 덮는다.
        return a;
    }

    @Override
    public void put(PhotoAnalysis analysis) {
        if (analysis.contentHash == null) return;
        if (!isCacheable(analysis)) return;

        AnalysisCacheEntity entry = new AnalysisCacheEntity();
        entry.mediaStoreId = analysis.mediaStoreId;
        entry.contentHash = analysis.contentHash;
        entry.lat = analysis.lat;
        entry.lng = analysis.lng;
        entry.source = analysis.source;
        entry.classification = analysis.classification;
        entry.takenAtUtc = analysis.takenAtUtc;
        entry.takenAtHasOffset = analysis.takenAtHasOffset;
        entry.createdAt = System.currentTimeMillis();
        db.analysisCacheDao().upsert(entry);
    }

    /**
     * GPS 로 확정된 결과만 캐시한다.
     *
     * <p>{@code UNKNOWN}(=GPS 없음)은 "위치가 없는 사진"이 아니라 <b>"아직 AI 를 안 돌린
     * 사진"</b>이다 — S1/S8 에는 AI 경로가 없어서 그렇게 끝났을 뿐이다. 이걸 캐시하면 S3 이
     * 붙었을 때 GPS 없는 사진이 전부 캐시 hit 으로 처리돼 영원히 AI 로 가지 못한다. 그래서
     * 이 가드는 호출부가 아니라 여기 있다 — 다음 슬라이스가 잊어버릴 수 없게.
     *
     * <p>S3 이 AI 결과를 캐시하기 시작하면 이 조건에 {@code source == AI} 를 더한다.
     */
    private static boolean isCacheable(PhotoAnalysis analysis) {
        return analysis.source == LocationSource.GPS;
    }
}
```

- [ ] **Step 6: 저장 경로가 `contentHash` 를 실제로 기록하게 한다**

`android/app/src/main/java/com/traveltrace/app/data/repo/RoomPhotoAnalysisRepository.java` 의 `saveTrip` 안, `p.mediaStoreId = a.mediaStoreId;` 줄 바로 다음에 추가:

```java
                p.contentHash = a.contentHash;
```

(S1 이 컬럼만 만들고 비워 뒀던 자리다 — 이제 실제 값이 들어간다.)

- [ ] **Step 7: DI 에 바인딩한다**

`android/app/src/main/java/com/traveltrace/app/di/AppModule.java` 의 `bindPhotoAnalysisRepository` 아래에 추가:

```java
    @Binds
    @Singleton
    public abstract AnalysisCacheStore bindAnalysisCacheStore(RoomAnalysisCacheStore impl);
```

import 에 `com.traveltrace.app.data.repo.RoomAnalysisCacheStore;` 와 `com.traveltrace.app.domain.AnalysisCacheStore;` 를 추가한다.

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*RoomAnalysisCacheStoreTest*'`
Expected: PASS (5 tests)

- [ ] **Step 9: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0

- [ ] **Step 10: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/domain/ \
  app/src/main/java/com/traveltrace/app/data/repo/ \
  app/src/main/java/com/traveltrace/app/di/AppModule.java \
  app/src/test/java/com/traveltrace/app/data/repo/RoomAnalysisCacheStoreTest.java
git commit -m "feat: add a trip-independent analysis cache store that only caches settled GPS results"
```

---

## Task 4: `AnalysisCostLog` — "AI 호출 0회"를 어서션 가능한 사실로 만들기

S8 수용 기준은 "저장 여행 열기 → AI 호출 0회(**비용 로그 확인**)"다. 지금은 AI 코드 자체가 없어서 0회가 자동으로 참이지만, 그건 검증이 아니라 우연이다. 카운터를 지금 세워 두면 S3/S4 가 AI 를 붙이는 순간 이 테스트가 회귀 그물이 된다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/core/AnalysisCostLog.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/VisionProviderStub.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/GeocoderStub.java`
- Test: `android/app/src/test/java/com/traveltrace/app/core/AnalysisCostLogTest.java`

**Interfaces:**
- Consumes: 없음 (독립)
- Produces:
  - `AnalysisCostLog.recordVisionCall()`, `recordGeocodeCall()`, `recordCacheHit()`, `recordCacheMiss()`, `reset()` → `void`
  - `AnalysisCostLog.visionCalls()`, `geocodeCalls()`, `cacheHits()`, `cacheMisses()` → `int`
  - `VisionProviderStub(AnalysisCostLog)`, `GeocoderStub(AnalysisCostLog)` 생성자

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/core/AnalysisCostLogTest.java`:

```java
package com.traveltrace.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.data.GeocoderStub;
import com.traveltrace.app.data.VisionProviderStub;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AnalysisCostLogTest {

    @Test
    public void countersStartAtZero() {
        AnalysisCostLog log = new AnalysisCostLog();

        assertEquals(0, log.visionCalls());
        assertEquals(0, log.geocodeCalls());
        assertEquals(0, log.cacheHits());
        assertEquals(0, log.cacheMisses());
    }

    @Test
    public void everyVisionCallIsCountedEvenWhenItThrows() {
        AnalysisCostLog log = new AnalysisCostLog();
        VisionProviderStub vision = new VisionProviderStub(log);

        try {
            vision.recognize(new byte[]{1, 2, 3});
            fail("스텁은 아직 미구현 예외를 던져야 한다");
        } catch (UnsupportedOperationException expected) {
            // 던지든 말든 "나갔다"는 사실이 비용이다 — 성공 응답만 세면 실패한 호출의
            // 토큰 비용이 통계에서 사라진다.
        }

        assertEquals("호출은 예외로 끝나도 세야 한다", 1, log.visionCalls());
    }

    @Test
    public void geocodeCallsAreCounted() {
        AnalysisCostLog log = new AnalysisCostLog();
        GeocoderStub geocoder = new GeocoderStub(log);

        geocoder.geocode(new GeocodeQuery.Poi("에펠탑"));

        assertEquals(1, log.geocodeCalls());
    }

    @Test
    public void resetClearsEveryCounter() {
        AnalysisCostLog log = new AnalysisCostLog();
        log.recordCacheHit();
        log.recordCacheMiss();
        log.recordGeocodeCall();

        log.reset();

        assertEquals(0, log.geocodeCalls());
        assertEquals(0, log.cacheHits());
        assertEquals(0, log.cacheMisses());
    }

    @Test
    public void countingIsSafeFromTheIoPool() throws Exception {
        // 배치는 io 스레드 여러 개에서 동시에 기록한다 — int++ 였다면 여기서 깨진다.
        AnalysisCostLog log = new AnalysisCostLog();
        AppExecutors executors = new AppExecutors();
        int perThread = 500;
        int threads = 4;
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executors.io().execute(() -> {
                for (int i = 0; i < perThread; i++) log.recordCacheHit();
                done.countDown();
            });
        }

        if (!done.await(5, TimeUnit.SECONDS)) fail("5초 안에 끝나야 한다");
        assertEquals(threads * perThread, log.cacheHits());
        executors.shutdown();
    }
}
```

> `GeocodeQuery.Poi` 의 정확한 생성 방법은 `core/model/GeocodeQuery.java` 를 열어 확인한다 —
> 중첩 클래스 생성자가 아니라 정적 팩토리(`GeocodeQuery.poi("에펠탑")`)일 수 있다. 그 파일의
> 실제 시그니처에 맞춘다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCostLogTest*'`
Expected: FAIL — `error: cannot find symbol: class AnalysisCostLog`

- [ ] **Step 3: `AnalysisCostLog` 를 만든다**

`android/app/src/main/java/com/traveltrace/app/core/AnalysisCostLog.java`:

```java
package com.traveltrace.app.core;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 유료 외부 호출과 캐시 효율의 집계 (PRD §4.7·§4.8).
 *
 * <p>존재 이유는 <b>주장을 검증 가능하게 만드는 것</b>이다. "저장된 여행을 열면 AI 호출이
 * 0회"는 지금은 AI 코드가 아예 없어서 우연히 참이지만, S3 이 붙는 순간 우연이 아니게 된다 —
 * 그때 이 카운터가 회귀 그물이 된다. 그래서 호출 경로가 생기기 전에 미리 세운다.
 *
 * <p>배치는 io 스레드 여러 개에서 동시에 기록하므로 전부 {@link AtomicInteger} 다.
 *
 * <p>금액 집계(여행당 비용 상한)는 S4 소관이라 여기 없다 — 지금은 "몇 번 나갔나"만 센다.
 */
@Singleton
public class AnalysisCostLog {

    private static final String TAG = "AnalysisCostLog";

    private final AtomicInteger visionCalls = new AtomicInteger();
    private final AtomicInteger geocodeCalls = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger cacheMisses = new AtomicInteger();

    @Inject
    public AnalysisCostLog() {
    }

    /** 호출이 예외로 끝나도 센다 — 실패한 호출도 토큰을 쓴다. */
    public void recordVisionCall() {
        visionCalls.incrementAndGet();
    }

    public void recordGeocodeCall() {
        geocodeCalls.incrementAndGet();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public int visionCalls() {
        return visionCalls.get();
    }

    public int geocodeCalls() {
        return geocodeCalls.get();
    }

    public int cacheHits() {
        return cacheHits.get();
    }

    public int cacheMisses() {
        return cacheMisses.get();
    }

    /** 새 배치를 시작할 때 부른다. */
    public void reset() {
        visionCalls.set(0);
        geocodeCalls.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /** 완료 요약을 logcat 에 남긴다 — "비용 로그 확인" 수용 기준의 육안 확인용. */
    public void logSummary(String label) {
        Log.i(TAG, label + " — vision=" + visionCalls() + " geocode=" + geocodeCalls()
                + " cacheHit=" + cacheHits() + " cacheMiss=" + cacheMisses());
    }
}
```

- [ ] **Step 4: 스텁이 호출을 기록하게 한다**

`android/app/src/main/java/com/traveltrace/app/data/VisionProviderStub.java` (전체 교체):

```java
package com.traveltrace.app.data;

import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Inject;

/**
 * 빈 스텁 (Epic D 에서 Gemini/OpenAI 실제 구현으로 대체). 아직 호출 경로가 없으므로 호출 시
 * 의도적으로 미구현 예외.
 *
 * <p>미구현이어도 {@link AnalysisCostLog} 에는 <b>먼저</b> 기록한다 — "저장 여행을 열면
 * vision 호출이 0회"라는 S8 수용 기준이 실제 구현으로 바뀐 뒤에도 계속 검증되려면, 호출을
 * 세는 지점이 구현이 아니라 이 경계에 있어야 한다.
 */
public class VisionProviderStub implements VisionProvider {

    private final AnalysisCostLog costLog;

    @Inject
    public VisionProviderStub(AnalysisCostLog costLog) {
        this.costLog = costLog;
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) {
        costLog.recordVisionCall();
        throw new UnsupportedOperationException("Epic D: GeminiProvider / OpenAiProvider 구현 예정");
    }
}
```

`android/app/src/main/java/com/traveltrace/app/data/GeocoderStub.java` (전체 교체):

```java
package com.traveltrace.app.data;

import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.domain.Geocoder;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** 빈 스텁 (Epic E 에서 Places/Geocoding 구현). 안전하게 빈 결과 반환. */
public class GeocoderStub implements Geocoder {

    private final AnalysisCostLog costLog;

    @Inject
    public GeocoderStub(AnalysisCostLog costLog) {
        this.costLog = costLog;
    }

    @Override
    public List<GeoPoint> geocode(GeocodeQuery query) {
        // 스텁이라 실제 네트워크는 안 나가지만 경계는 여기다 — 실 구현으로 바뀌어도
        // 카운터 지점이 그대로 남는다.
        costLog.recordGeocodeCall();
        return Collections.emptyList();
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCostLogTest*'`
Expected: PASS (5 tests)

- [ ] **Step 6: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0. (`new VisionProviderStub()` / `new GeocoderStub()` 를 인자 없이 부르는 테스트가 있으면 `new AnalysisCostLog()` 를 넘기도록 고친다.)

- [ ] **Step 7: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/core/AnalysisCostLog.java \
  app/src/main/java/com/traveltrace/app/data/VisionProviderStub.java \
  app/src/main/java/com/traveltrace/app/data/GeocoderStub.java \
  app/src/test/java/com/traveltrace/app/core/AnalysisCostLogTest.java
git commit -m "feat: count vision/geocode calls and cache hits so zero-call claims are assertable"
```

---

## Task 5: 분석 배치에 캐시를 끼운다 — 해시 → 조회 → miss 일 때만 EXIF

여기가 슬라이스의 심장이다. 같은 사진을 두 번째 여행에서 다시 고르면 EXIF 를 다시 파싱하지 않고 저장된 결과를 그대로 쓴다. S3 이 붙으면 같은 자리에서 업로드·AI·지오코딩이 통째로 스킵된다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java` (생성자 추종)
- Test: `android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisCacheReuseTest.java`

**Interfaces:**
- Consumes: Task 1 `ContentHasher.hash(Uri, long)`, `GalleryImage.sizeBytes`; Task 3 `AnalysisCacheStore.get/put`, `PhotoAnalysis.contentHash`; Task 4 `AnalysisCostLog`
- Produces: `AnalysisViewModel(Context, MediaStoreImageSource, ExifExtractor, PhotoAnalysisRepository, SelectionSession, AppExecutors, ContentHasher, AnalysisCacheStore, AnalysisCostLog)` — 인자 9개

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisCacheReuseTest.java`:

```java
package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.ContentHasher;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.data.repo.RoomAnalysisCacheStore;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.ui.selection.SelectionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.fakes.RoboCursor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 같은 사진을 두 여행에서 연달아 분석했을 때, 두 번째 분석이 EXIF 를 다시 읽지 않고
 * 캐시된 결과를 쓰는지 검증한다 (S8 수용 기준: "같은 사진 재분석 시 캐시 hit, 다른
 * 여행에서도 hit").
 */
@RunWith(RobolectricTestRunner.class)
public class AnalysisCacheReuseTest {

    /** extract() 가 실제로 몇 번 불렸는지 세는 스파이. Mockito 없이 상속으로 만든다. */
    private static class CountingExifExtractor extends ExifExtractor {
        final AtomicInteger extractions = new AtomicInteger();

        CountingExifExtractor(Context ctx) {
            super(ctx, TimeZone.getTimeZone("Asia/Seoul"));
        }

        @Override
        public PhotoAnalysis extract(GalleryImage image) {
            extractions.incrementAndGet();
            return super.extract(image);
        }
    }

    private Context ctx;
    private AppExecutors executors;
    private TravelTraceDatabase db;
    private SelectionSession session;
    private RoomTripRepository tripRepo;
    private AnalysisCostLog costLog;
    private CountingExifExtractor extractor;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new SelectionSession();
        tripRepo = new RoomTripRepository(db, executors);
        costLog = new AnalysisCostLog();
        extractor = new CountingExifExtractor(ctx);

        seedGallery();
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    /** GPS 가 있는 사진 2장. 크기까지 커서에 실어야 해시가 크기를 섞을 수 있다. */
    private void seedGallery() throws Exception {
        long sizeOne = registerJpeg(1L, "2024:06:12 10:12:00");
        long sizeTwo = registerJpeg(2L, "2024:06:12 11:12:00");

        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE));
        cursor.setResults(new Object[][]{
                {1L, "a.jpg", 1_718_154_720_000L, sizeOne},
                {2L, "b.jpg", 1_718_158_320_000L, sizeTwo}});
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);
    }

    /**
     * 배치를 두 번 돌리므로 각 Uri 가 여러 번 열린다 — registerInputStream 은 스트림
     * 인스턴스를 하나만 들고 있어 두 번째 open 이 고갈된 스트림을 받는다. 반드시
     * registerInputStreamSupplier 로 매번 새 스트림을 만들어야 한다.
     *
     * <p>ExifExtractor 는 setRequireOriginal(uri) 를, ContentHasher 는 평범한 uri 를
     * 연다(설계 결정 2) — 서로 다른 Uri 라 양쪽 모두 등록해야 한다.
     */
    private long registerJpeg(long id, String dateTime) throws Exception {
        File file = new File(ctx.getCacheDir(), id + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setLatLong(48.85 + id / 100d, 2.29 + id / 100d);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        exif.saveAttributes();

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build();
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(uri, () -> open(file));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(MediaStore.setRequireOriginal(uri), () -> open(file));
        return file.length();
    }

    private static FileInputStream open(File file) {
        try {
            return new FileInputStream(file);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private AnalysisViewModel newViewModel() {
        return new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                extractor,
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors,
                new ContentHasher(ctx),
                new RoomAnalysisCacheStore(db),
                costLog);
    }

    /** 선택된 사진으로 배치를 1회 돌리고 저장된 tripId 를 돌려준다. */
    private String analyze(Long... ids) {
        session.put(Arrays.asList(ids));
        AnalysisViewModel vm = newViewModel();
        return AsyncTestHarness.awaitLiveData(
                vm.savedTripId(), vm::start, id -> id != null, "AnalysisViewModel.start()");
    }

    @Test
    public void reanalyzingTheSamePhotosInANewTripHitsTheCacheAndSkipsExif() {
        String firstTrip = analyze(1L, 2L);
        assertNotNull(firstTrip);
        assertEquals("첫 분석은 두 장 모두 EXIF 를 읽는다", 2, extractor.extractions.get());
        assertEquals("첫 분석은 두 장 모두 miss", 2, costLog.cacheMisses());
        assertEquals(0, costLog.cacheHits());

        String secondTrip = analyze(1L, 2L);
        assertNotNull(secondTrip);
        assertTrue("두 번째는 새 여행이어야 한다", !secondTrip.equals(firstTrip));

        assertEquals("두 번째 분석은 EXIF 를 한 장도 다시 읽지 않는다 — 캐시가 여행을 넘는다",
                2, extractor.extractions.get());
        assertEquals(2, costLog.cacheHits());
    }

    @Test
    public void aCacheHitStillProducesACompleteSavedTrip() {
        analyze(1L, 2L);
        String secondTrip = analyze(1L, 2L);

        TripDetail detail = AsyncTestHarness.awaitCallback(cb -> tripRepo.open(secondTrip, cb));

        assertNotNull(detail);
        assertEquals("캐시로 만든 여행도 좌표·시각이 전부 살아 있어야 한다",
                2, detail.stops.size());
        assertNotNull(detail.stops.get(0).lat);
        assertNotNull(detail.stops.get(0).takenAtUtc);
        assertEquals("파일명은 캐시가 아니라 살아 있는 MediaStore 값에서 온다",
                "a.jpg", detail.stops.get(0).displayName);
    }

    @Test
    public void noVisionOrGeocodeCallEverHappens() {
        analyze(1L, 2L);
        analyze(1L, 2L);

        assertEquals("GPS 사진은 AI 를 부르지 않는다 (PRD §4.2 우선순위)", 0, costLog.visionCalls());
        assertEquals(0, costLog.geocodeCalls());
    }

    @Test
    public void theContentHashIsPersistedOnThePhotoRow() {
        analyze(1L);

        assertEquals("캐시 행이 실제로 쓰였는지 — 사진 1장이면 항목 1개",
                1, db.analysisCacheDao().count());
    }

    @Test
    public void analyzingASubsetLaterStillHitsForThePhotosItShares() {
        analyze(1L, 2L);
        int afterFirst = extractor.extractions.get();

        analyze(1L);

        assertEquals("겹치는 한 장은 hit — 다른 선택 조합이어도 캐시는 사진 단위다",
                afterFirst, extractor.extractions.get());
        assertEquals(1, costLog.cacheHits());
    }
}
```

> `SelectionSession.put(...)` 의 정확한 시그니처는 `ui/selection/SelectionSession.java` 에서
> 확인한다(`put(List<Long>)`). `analyze()` 헬퍼가 그것에 맞게 호출하는지 확인할 것.
> `costLog` 는 여러 테스트에서 누적되므로 각 테스트가 **새 인스턴스**를 받는다(`@Before` 에서
> 새로 만든다) — 위 코드는 그렇게 되어 있다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCacheReuseTest*'`
Expected: FAIL — `error: constructor AnalysisViewModel ... cannot be applied to given types` (인자 9개를 아직 못 받는다)

- [ ] **Step 3: `AnalysisViewModel` 에 세 의존을 주입한다**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java` — 필드 선언부에서 `private final AppExecutors executors;` 바로 아래에 추가:

```java
    private final ContentHasher hasher;
    private final AnalysisCacheStore cacheStore;
    private final AnalysisCostLog costLog;
```

생성자를 통째로 교체한다:

```java
    @Inject
    public AnalysisViewModel(@ApplicationContext Context context,
                             MediaStoreImageSource imageSource,
                             ExifExtractor extractor,
                             PhotoAnalysisRepository analysisRepository,
                             SelectionSession session,
                             AppExecutors executors,
                             ContentHasher hasher,
                             AnalysisCacheStore cacheStore,
                             AnalysisCostLog costLog) {
        this.context = context;
        this.imageSource = imageSource;
        this.extractor = extractor;
        this.analysisRepository = analysisRepository;
        this.session = session;
        this.executors = executors;
        this.hasher = hasher;
        this.cacheStore = cacheStore;
        this.costLog = costLog;
    }
```

import 에 다음을 추가한다:

```java
import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.data.media.ContentHasher;
import com.traveltrace.app.domain.AnalysisCacheStore;
```

- [ ] **Step 4: 배치 루프에 캐시를 끼운다**

같은 파일의 `run(List<GalleryImage> targets)` 메서드에서 다음 두 줄을

```java
            GalleryImage image = targets.get(i);
            PhotoAnalysis analysis = extractor.extract(image);
```

아래로 교체한다:

```java
            GalleryImage image = targets.get(i);
            PhotoAnalysis analysis = analyze(image);
```

그리고 `run(...)` 메서드 바로 아래에 새 메서드를 추가한다:

```java
    /**
     * 사진 1장을 해석한다: 해시 → 캐시 조회 → miss 일 때만 실제 판독.
     *
     * <p>순서가 중요하다. 해시를 앞 64KiB 로만 계산하기 때문에(plan/04 결정) 캐시 조회를
     * EXIF 파싱 <em>앞에</em> 둘 수 있고, 그래야 hit 이 실제로 일을 줄인다. S3 이 붙으면
     * 이 자리에서 다운스케일·업로드·AI·지오코딩이 통째로 스킵된다 — 그때 절약되는 것은
     * 로컬 파싱이 아니라 돈이다.
     *
     * <p>해시를 못 구했으면(사진이 지워졌거나 권한이 빠졌다) 캐시를 통째로 건너뛰고 평소대로
     * 판독한다 — 캐시는 최적화지 정확성의 전제가 아니다.
     */
    private PhotoAnalysis analyze(GalleryImage image) {
        String hash = hasher.hash(image.contentUri, image.sizeBytes);

        if (hash != null) {
            // GalleryImage 쪽 필드명은 id, PhotoAnalysis 쪽은 mediaStoreId 다 — 같은 값이다.
            PhotoAnalysis cached = cacheStore.get(image.id, hash);
            if (cached != null) {
                costLog.recordCacheHit();
                // 파일명은 콘텐츠가 아니라 MediaStore 행의 속성이라 캐시가 들고 있지 않다 —
                // 살아 있는 커서 값으로 채운다(사용자가 이름을 바꿨을 수 있다).
                cached.displayName = image.displayName;
                return cached;
            }
        }

        costLog.recordCacheMiss();
        PhotoAnalysis fresh = extractor.extract(image);
        fresh.contentHash = hash;
        // put() 이 캐시 가능 여부를 스스로 판단한다 — 여기서 거르지 않는다.
        cacheStore.put(fresh);
        return fresh;
    }
```

- [ ] **Step 5: 배치 시작 시 카운터를 초기화하고 완료 시 요약을 남긴다**

같은 파일의 `start()` 안, `extractor.resetFailureCount();` 줄 바로 아래에 추가:

```java
        costLog.reset();
```

`run(...)` 안 `analysisRepository.saveTrip(...)` 콜백에서 `session.clear();` 바로 위에 추가:

```java
            costLog.logSummary("analyze " + total + "장");
```

- [ ] **Step 6: 기존 `AnalysisPipelineTest` 의 생성자 호출을 *전부* 맞춘다**

Run: `cd android && grep -rn "new AnalysisViewModel(" app/src/test`

**호출부는 2곳이다** — `setUp()` 의 `vm = new AnalysisViewModel(...)` 와, `sessionClearOrderingSurvivesACommitThenCancelRace()` 안에서 `CommitThenCancelRepository` 페이크를 물려 만드는 `AnalysisViewModel raceVm = new AnalysisViewModel(...)`. 테스트 소스는 한 번에 컴파일되므로 **한 곳만 고치면 테스트 모듈 전체가 컴파일 실패**한다(테스트 1개 실패가 아니라 전부 빨개진다).

`setUp()` 쪽:

```java
        vm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors,
                new com.traveltrace.app.data.media.ContentHasher(ctx),
                new com.traveltrace.app.data.repo.RoomAnalysisCacheStore(db),
                new com.traveltrace.app.core.AnalysisCostLog());
```

`sessionClearOrderingSurvivesACommitThenCancelRace()` 안의 `raceVm` 쪽도 같은 3개를 덧붙인다. 이 테스트가 검증하는 것은 세션 clear 순서지 캐시가 아니므로, 페이크 리포지토리는 그대로 두고 나머지는 실제 구현을 넘긴다:

```java
        AnalysisViewModel raceVm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                fakeRepo,
                session,
                executors,
                new com.traveltrace.app.data.media.ContentHasher(ctx),
                new com.traveltrace.app.data.repo.RoomAnalysisCacheStore(db),
                new com.traveltrace.app.core.AnalysisCostLog());
```

(`fakeRepo` 는 바로 윗줄에서 만드는 `CommitThenCancelRepository` 인스턴스다. 그 아래
`fakeRepo.attachTo(raceVm);` 줄은 그대로 둔다.)

같은 파일의 `seedGallery()` 에서 `RoboCursor` 컬럼 목록에 `MediaStore.Images.Media.SIZE` 를, 각 행 끝에 크기 값을 더한다(Task 1 Step 8 과 같은 이유 — `getColumnIndexOrThrow` 가 던진다):

```java
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE));
        cursor.setResults(new Object[][]{
                {1L, "a.jpg", 1_718_154_720_000L, 2048L},
                {2L, "b.jpg", 1_718_158_320_000L, 2048L},
                {3L, "c.jpg", 1_718_161_920_000L, 2048L}});
```

또한 `registerJpeg(...)` 의 `registerInputStream` 을 `registerInputStreamSupplier` 로 바꾸고 **평범한 uri 에도** 등록한다 — 이제 `ContentHasher` 가 그 URI 를 열기 때문이다:

```java
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(uri, () -> openQuietly(file));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(MediaStore.setRequireOriginal(uri),
                        () -> openQuietly(file));
```

그리고 같은 클래스에 헬퍼를 추가한다:

```java
    private static FileInputStream openQuietly(File file) {
        try {
            return new FileInputStream(file);
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
```

- [ ] **Step 7: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*AnalysisCacheReuseTest*' --tests '*AnalysisPipelineTest*'`
Expected: PASS (신규 5개 + 기존 `AnalysisPipelineTest` 전부)

- [ ] **Step 8: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0

- [ ] **Step 9: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java \
  app/src/test/java/com/traveltrace/app/ui/analysis/
git commit -m "feat: consult the analysis cache before reading EXIF so repeat photos skip the work"
```

---

## Task 6: 오프라인 감지 + MAP 한계 배너

경로 폴리라인은 저장 메타데이터만으로 그려지지만 **지도 타일은 네트워크가 필요하다.** 오프라인이면 회색 배경 위에 선만 뜨는데, 안내가 없으면 사용자는 앱이 고장났다고 읽는다. 상단바 아래 배너로 "경로는 보인다 / 지도 배경만 못 불러온다"를 정확히 말한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/core/net/ConnectivityMonitor.java`
- Create: `android/app/src/main/res/layout/view_offline_banner.xml`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Modify: `android/app/src/main/res/layout/fragment_map_replay.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelTest.java`, `MapReplayViewModelLoadTest.java` (생성자 추종 — `MapReplayViewModelArgsTest` 는 ViewModel 을 직접 만들지 않아 손댈 곳이 없다)
- Test: `android/app/src/test/java/com/traveltrace/app/core/net/ConnectivityMonitorTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/FakeConnectivity.java` (두 ViewModel 테스트가 공유)
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapOfflineBannerRendererTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapOfflineBannerScreenshotTest.java`
- Golden: `android/app/src/test/screenshots/map_offline_banner.png` (신규 1장, 기존 14장 불변)

**Interfaces:**
- Consumes: 없음 (`MapUiState`·`MapReplayViewModel` 은 기존)
- Produces:
  - `ConnectivityMonitor.isOnline()` → `boolean` (오버라이드 가능하도록 `public`, 클래스 non-final)
  - `MapUiState.offline` (`public final boolean`), 9인자 생성자, `MapUiState.withOffline(boolean)` → `MapUiState`
  - `MapRenderer.renderOfflineBanner(ViewOfflineBannerBinding, MapUiState)` → `void`
  - `MapReplayViewModel(SavedStateHandle, TripRepository, ConnectivityMonitor)`, `MapReplayViewModel.refreshConnectivity()` → `void`

- [ ] **Step 1: 실패하는 테스트 2개를 쓴다**

`android/app/src/test/java/com/traveltrace/app/core/net/ConnectivityMonitorTest.java`:

```java
package com.traveltrace.app.core.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;

@RunWith(RobolectricTestRunner.class)
public class ConnectivityMonitorTest {

    private Context ctx;
    private ConnectivityManager cm;
    private ConnectivityMonitor monitor;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        monitor = new ConnectivityMonitor(ctx);
    }

    @Test
    public void aNetworkWithInternetCapabilityIsOnline() {
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(cm).setNetworkCapabilities(network, caps);

        assertTrue(monitor.isOnline());
    }

    @Test
    public void aNetworkWithoutInternetCapabilityIsOffline() {
        Network network = cm.getActiveNetwork();
        // 비행기 모드/캡티브 포털처럼 "연결은 있는데 인터넷은 없는" 상태.
        Shadows.shadowOf(cm).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance());

        assertFalse(monitor.isOnline());
    }

    @Test
    public void noCapabilitiesAtAllIsOfflineAndDoesNotThrow() {
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.getActiveNetwork(), null);

        assertFalse("판단할 근거가 없으면 오프라인으로 본다 — 안내가 한 번 더 뜨는 쪽이 "
                + "'오프라인 지도'라고 오해하게 두는 쪽보다 안전하다", monitor.isOnline());
    }
}
```

`android/app/src/test/java/com/traveltrace/app/ui/map/MapOfflineBannerRendererTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewOfflineBannerBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapOfflineBannerRendererTest {

    private ViewOfflineBannerBinding binding;

    @Before
    public void setUp() {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewOfflineBannerBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void onlineStateHidesTheBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map());

        assertEquals(View.GONE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void offlineStateShowsTheBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));

        assertEquals(View.VISIBLE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void theCopyPromisesTheRouteAndDeniesTheMapBackground() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));
        String text = binding.offlineBannerText.getText().toString();

        assertTrue("경로는 보인다고 말해야 한다: " + text, text.contains("경로"));
        assertTrue("지도(배경/타일)를 못 불러온다고 말해야 한다: " + text, text.contains("지도"));
    }

    @Test
    public void cinemaModeHidesTheBannerEvenWhenOffline() {
        MapUiState offlineCinema = new MapUiState("여행", 0, ScreenFixtures.map().stops, 0,
                false, false, true, MapUiState.Speed.NORMAL, true);

        MapRenderer.renderOfflineBanner(binding, offlineCinema);

        assertEquals("상영 모드는 크롬을 전부 숨긴다 — 배너도 예외가 아니다",
                View.GONE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void withOfflinePreservesEveryOtherFieldAndTheStopInstances() {
        MapUiState base = ScreenFixtures.map();

        MapUiState offline = base.withOffline(true);

        assertEquals(base.tripTitle, offline.tripTitle);
        assertEquals(base.unknownCount, offline.unknownCount);
        assertEquals(base.activeIndex, offline.activeIndex);
        assertEquals(base.speed, offline.speed);
        assertTrue("Stop 인스턴스를 새로 찍으면 MapReplayFragment.sameRoute() 가드가 깨져 "
                        + "지도가 다시 그려지고 카메라가 스냅된다",
                base.stops.get(0) == offline.stops.get(0));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ConnectivityMonitorTest*' --tests '*MapOfflineBannerRendererTest*'`
Expected: FAIL — `error: cannot find symbol: class ConnectivityMonitor`

- [ ] **Step 3: `ConnectivityMonitor` 를 만든다**

`android/app/src/main/java/com/traveltrace/app/core/net/ConnectivityMonitor.java`:

```java
package com.traveltrace.app.core.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * "지금 지도 타일을 받아올 수 있는가"를 판정한다 (PRD §5 오프라인).
 *
 * <p>저장된 여행의 <em>재생</em>은 네트워크가 없어도 완결된다 — 좌표·시각·이름이 전부 Room 에
 * 있기 때문이다. 하지만 <b>지도 타일은 2D·3D 모두 네트워크가 필요하다.</b> 그래서 이 판정의
 * 용도는 재생을 막는 것이 아니라 <em>배경이 비는 이유를 설명하는 것</em>뿐이다.
 *
 * <p>클래스와 {@link #isOnline()} 은 일부러 final 이 아니다 — MAP ViewModel 테스트가
 * ConnectivityManager 섀도를 세우는 대신 상속으로 온/오프라인을 고정할 수 있게 한다.
 */
@Singleton
public class ConnectivityMonitor {

    private final Context context;

    @Inject
    public ConnectivityMonitor(@ApplicationContext Context context) {
        this.context = context;
    }

    /** 판단 근거가 없으면 false — 안내가 한 번 더 뜨는 쪽이 오해를 남기는 쪽보다 안전하다. */
    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network active = cm.getActiveNetwork();
        if (active == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
```

- [ ] **Step 4: `MapUiState` 에 `offline` 을 더한다**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java` — `public final Speed speed;` 아래에 필드를 추가하고, 기존 8인자 생성자를 **유지한 채** 9인자 생성자와 `withOffline` 을 더한다. 8인자 생성자를 남기는 이유는 이 상태를 직접 만드는 지점이 12곳이라 전부 고치면 무관한 회귀 위험만 커지기 때문이다:

```java
    public final Speed speed;

    /**
     * 지도 타일을 받아올 수 없는 상태. 경로 폴리라인은 저장된 좌표만으로 그려지므로 이 값이
     * true 여도 재생 자체는 정상이다 — 배경이 비는 이유를 안내하는 데만 쓴다(PRD §5).
     */
    public final boolean offline;

    /** offline=false 인 기존 8인자 형태. 오프라인을 모르는 호출부(픽스처·테스트)가 쓴다. */
    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed) {
        this(tripTitle, unknownCount, stops, activeIndex, playing, satellite, cinema, speed, false);
    }

    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed,
                      boolean offline) {
        this.tripTitle = tripTitle;
        this.unknownCount = unknownCount;
        this.stops = Collections.unmodifiableList(new ArrayList<>(stops));
        this.activeIndex = activeIndex;
        this.playing = playing;
        this.satellite = satellite;
        this.cinema = cinema;
        this.speed = speed;
        this.offline = offline;
    }

    /**
     * 연결 상태만 갈아끼운 복제본. <b>Stop 인스턴스는 그대로 넘긴다</b> —
     * {@link MapReplayFragment#sameRoute} 가 참조 동일성으로 "경로가 바뀌었는가"를 판별하므로,
     * 여기서 Stop 을 새로 찍으면 오프라인 배너가 뜨고 지는 것만으로 지도가 다시 그려지고
     * 카메라가 전체 경로 bounds 로 스냅된다.
     */
    public MapUiState withOffline(boolean offline) {
        if (this.offline == offline) return this;
        return new MapUiState(tripTitle, unknownCount, stops, activeIndex,
                playing, satellite, cinema, speed, offline);
    }
```

기존 8인자 생성자 본문(`this.tripTitle = ...` 이하)은 9인자 쪽으로 옮겼으므로 **중복해서 남기지 않는다.**

- [ ] **Step 5: 배너 레이아웃과 문자열을 만든다**

`android/app/src/main/res/layout/view_offline_banner.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- MAP 오프라인 한계 배너: 상단바 아래에 상시 노출. 토스트가 아니라 배너인 이유는
     "오프라인 지도"라는 오해를 오프라인인 동안 계속 막아야 하기 때문이다(PRD §5). -->
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/offlineBannerRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="14dp"
    android:layout_marginEnd="14dp"
    android:background="@drawable/bg_chip_unknown"
    android:elevation="@dimen/card_elevation"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingStart="12dp"
    android:paddingTop="9dp"
    android:paddingEnd="12dp"
    android:paddingBottom="9dp"
    android:visibility="gone">

    <ImageView
        android:layout_width="14dp"
        android:layout_height="14dp"
        android:contentDescription="@null"
        android:src="@drawable/ic_info"
        app:tint="@color/unknown_yellow" />

    <TextView
        android:id="@+id/offlineBannerText"
        style="@style/TextAppearance.TravelTrace.Small"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:layout_weight="1"
        android:text="@string/map_offline_notice"
        android:textColor="@color/text_on_fill"
        android:textFontWeight="700"
        tools:text="지도 배경을 불러올 수 없어요" />
</LinearLayout>
```

`android/app/src/main/res/values/strings.xml` 의 `<!-- MAP -->` 섹션, `map_unknown_chip` 줄 바로 다음에 추가:

```xml
    <!-- 오프라인 한계 안내. "오프라인 지도"가 아님을 정확히 말해야 한다(PRD §5) —
         저장된 경로는 그려지지만 지도 타일은 2D·3D 모두 네트워크가 필요하다. -->
    <string name="map_offline_notice">지도 배경은 못 불러왔어요. 저장된 경로는 그대로 볼 수 있어요.</string>
```

- [ ] **Step 6: `MapRenderer` 에 배너 렌더를 더한다**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java` — `renderTopBar` 메서드 바로 아래에 추가:

```java
    /**
     * 오프라인 한계 배너. 상영 모드에선 다른 크롬과 함께 숨는다 — 상영 중에 배너만 남으면
     * 연출이 깨지고, 어차피 상영을 나오면 다시 보인다.
     */
    public static void renderOfflineBanner(ViewOfflineBannerBinding binding, MapUiState state) {
        binding.offlineBannerRoot.setVisibility(
                state.offline && !state.cinema ? View.VISIBLE : View.GONE);
    }
```

import 에 `com.traveltrace.app.databinding.ViewOfflineBannerBinding;` 를 추가한다.

- [ ] **Step 7: 테스트가 통과하는 것을 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest --tests '*ConnectivityMonitorTest*' --tests '*MapOfflineBannerRendererTest*'`
Expected: PASS (8 tests)

`ConnectivityMonitorTest` 가 Robolectric 섀도 API 불일치(`ShadowNetworkCapabilities.newInstance()` / `setNetworkCapabilities` 시그니처)로 컴파일되지 않으면, 그 테스트만 `ConnectivityMonitor` 를 상속해 `isOnline()` 을 고정하는 형태로 대체하지 말고 — 그건 프로덕션 코드를 전혀 검증하지 못한다 — Robolectric 4.14.1 의 실제 `ShadowConnectivityManager` API 를 확인해 맞춘다.

- [ ] **Step 8: ViewModel 이 연결 상태를 상태에 실어 보내게 한다**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java` — 필드에 추가:

```java
    private final ConnectivityMonitor connectivity;
```

생성자를 교체:

```java
    @Inject
    public MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository,
                              ConnectivityMonitor connectivity) {
        this.savedState = savedState;
        this.tripRepository = tripRepository;
        this.connectivity = connectivity;
    }
```

`load()` 안의 두 `state.setValue(...)` 를 오프라인 여부까지 실어 보내도록 바꾼다:

```java
        tripRepository.open(tripId, detail -> {
            if (detail == null) {
                state.setValue(new MapUiState("", 0, new ArrayList<>(), 0,
                        false, false, false, MapUiState.Speed.NORMAL, !connectivity.isOnline()));
                return;
            }
            state.setValue(toState(detail).withOffline(!connectivity.isOnline()));
        });
```

`tripId == null` 인 프리뷰 분기도 같은 방식으로:

```java
        if (tripId == null) {
            state.setValue(ScreenFixtures.map().withOffline(!connectivity.isOnline()));
            return;
        }
```

`copy(...)` 가 `offline` 을 잃지 않게 마지막 인자를 더한다:

```java
    private static MapUiState copy(MapUiState s, int activeIndex, boolean playing,
                                   boolean satellite, boolean cinema, MapUiState.Speed speed) {
        return new MapUiState(s.tripTitle, s.unknownCount, s.stops, activeIndex,
                playing, satellite, cinema, speed, s.offline);
    }
```

그리고 화면으로 돌아왔을 때 다시 확인하는 훅을 `load()` 아래에 추가한다:

```java
    /**
     * 화면으로 돌아올 때 연결 상태만 다시 확인한다. NetworkCallback 을 등록하지 않는 것은
     * 의도다 — 배너 하나를 위해 콜백 생명주기를 관리할 값어치가 없고, 사용자가 비행기 모드를
     * 끄고 돌아오는 흐름은 onResume 으로 충분히 잡힌다.
     */
    public void refreshConnectivity() {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(s.withOffline(!connectivity.isOnline()));
    }
```

import 에 `com.traveltrace.app.core.net.ConnectivityMonitor;` 를 추가한다.

- [ ] **Step 9: Fragment 가 배너를 렌더하고 복귀 시 재확인하게 한다**

`android/app/src/main/res/layout/fragment_map_replay.xml` — `mapTopBar` include 바로 다음에 추가:

```xml
    <include
        android:id="@+id/offlineBanner"
        layout="@layout/view_offline_banner"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/mapTopBar" />
```

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java` — `render(MapUiState state)` 안, `MapRenderer.renderTopBar(binding.mapTopBar, state);` 바로 다음 줄에 추가:

```java
        MapRenderer.renderOfflineBanner(binding.offlineBanner, state);
```

그리고 `onViewCreated` 아래에 `onResume` 을 추가한다:

```java
    @Override
    public void onResume() {
        super.onResume();
        // 설정에서 비행기 모드를 끄고 돌아왔을 수 있다 — 배너를 최신 상태로 맞춘다.
        if (vm != null) vm.refreshConnectivity();
    }
```

- [ ] **Step 10: `MapReplayViewModel` 을 직접 생성하는 테스트를 맞춘다**

Run: `cd android && grep -rn "new MapReplayViewModel(" app/src/test`

2026-07-20 기준 **2곳**이다 — `MapReplayViewModelTest`(`new MapReplayViewModel(new SavedStateHandle(), null)`)와 `MapReplayViewModelLoadTest`(`new MapReplayViewModel(handle, tripRepo)`). 두 파일이 공유할 헬퍼를 새로 만든다:

`android/app/src/test/java/com/traveltrace/app/ui/map/FakeConnectivity.java`:

```java
package com.traveltrace.app.ui.map;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.net.ConnectivityMonitor;

/**
 * MAP ViewModel 테스트용 연결 상태 고정. ConnectivityManager 섀도를 세우는 대신 상속으로
 * 답을 박는다 — 이 테스트들이 보는 건 연결 판정이 아니라 그 결과가 상태에 실리는지다
 * (판정 자체는 ConnectivityMonitorTest 가 본다).
 */
public final class FakeConnectivity {

    private FakeConnectivity() {}

    public static ConnectivityMonitor online() {
        return fixed(true);
    }

    public static ConnectivityMonitor offline() {
        return fixed(false);
    }

    private static ConnectivityMonitor fixed(boolean online) {
        return new ConnectivityMonitor(ApplicationProvider.getApplicationContext()) {
            @Override
            public boolean isOnline() {
                return online;
            }
        };
    }
}
```

두 호출부를 각각 `new MapReplayViewModel(new SavedStateHandle(), null, FakeConnectivity.online())` 와 `new MapReplayViewModel(handle, tripRepo, FakeConnectivity.online())` 로 바꾼다. 두 파일 모두 같은 패키지라 import 는 필요 없다.

`MapReplayViewModelLoadTest` 에 오프라인 경로 테스트도 하나 더한다 — ViewModel 이 연결 상태를 실제로 상태에 싣는지는 Renderer 테스트가 볼 수 없는 부분이다:

```java
    @Test
    public void anOfflineDeviceMarksTheLoadedTripAsOffline() {
        SavedStateHandle handle = new SavedStateHandle();
        handle.set(MapReplayFragment.ARG_TRIP_ID, saveTrip());
        MapReplayViewModel vm =
                new MapReplayViewModel(handle, tripRepo, FakeConnectivity.offline());

        MapUiState state = AsyncTestHarness.awaitLiveData(
                vm.state(), vm::load, s -> s != null, "MapReplayViewModel.load() offline");

        assertTrue("오프라인이어도 경로는 그대로 실린다", state.stops.size() == 2);
        assertTrue("배너를 띄울 근거가 상태에 실려야 한다", state.offline);
    }
```

- [ ] **Step 11: 골든 스크린샷 테스트를 추가하고 기록한다**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapOfflineBannerScreenshotTest.java`:

```java
package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.ViewOfflineBannerBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 오프라인 배너를 PNG 로 캡처한다. 상단바와 같은 이유로 include 레이아웃만 단독 캡처한다 —
 * MAP 화면 전체는 SupportMapFragment 때문에 Robolectric 이 인플레이트할 수 없다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class MapOfflineBannerScreenshotTest {

    private ScreenshotHarness harness;
    private ViewOfflineBannerBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = ViewOfflineBannerBinding.inflate(harness.inflater());
    }

    @Test
    public void offlineBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));
        harness.captureWrapContentHeight(binding.getRoot(), "map_offline_banner.png");
    }
}
```

골든을 기록한다:

Run: `cd android && sh gradlew :app:testDebugUnitTest -Proborazzi.record --tests '*MapOfflineBannerScreenshotTest*'`
Expected: PASS — `app/src/test/screenshots/map_offline_banner.png` 가 생긴다.

Run: `cd android && git status --short app/src/test/screenshots`
Expected: `?? app/src/test/screenshots/map_offline_banner.png` 하나만. **기존 14장 중 하나라도 `M` 으로 뜨면 되돌린다** — S8은 기존 골든을 바꾸지 않는다.

- [ ] **Step 12: 전체 회귀를 확인한다**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0 (골든 15장 전부 verify 통과)

- [ ] **Step 13: 커밋**

```bash
cd android && git add app/src/main/java/com/traveltrace/app/core/net/ \
  app/src/main/java/com/traveltrace/app/ui/map/ \
  app/src/main/res/layout/view_offline_banner.xml \
  app/src/main/res/layout/fragment_map_replay.xml \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/traveltrace/app/core/net/ \
  app/src/test/java/com/traveltrace/app/ui/map/ \
  app/src/test/screenshots/map_offline_banner.png
git commit -m "feat: tell the user map tiles need network while still replaying the saved route offline"
```

---

## Task 7: README 신설 · 설계 결정 역기록 · 슬라이스 상태 갱신

plan/04 는 해시 결정을 "확정하고 역기록하라"고 지시했고, plan/15 는 캐시의 기기 로컬 한계를 "README·앱에 명시"하라고 요구한다. 저장소에 README 가 아직 없으므로 여기서 만든다. 코드가 아니라 문서지만, 이걸 빼면 두 수용 기준이 미충족이다.

**Files:**
- Create: `README.md` (저장소 루트)
- Modify: `plan/04-data-layer.md`
- Modify: `plan/15-trip-persistence-offline.md`
- Modify: `plan/ISSUES.md`
- Modify: `plan/00-overview.md`

**Interfaces:**
- Consumes: Task 1~6 의 확정 사실 (해시 방식, 캐시 테이블, 오프라인 카피)
- Produces: 없음 (문서)

- [ ] **Step 1: README 를 만든다**

저장소 루트 `README.md`:

```markdown
# TravelTrace

갤러리 여행 사진을 촬영 시각순으로 지도 위 경로로 그려 다시 재생하는 Android 앱.

- 기획: [PRD.md](PRD.md)
- 구현 계획: [plan/00-overview.md](plan/00-overview.md) · 수직 슬라이스 [plan/ISSUES.md](plan/ISSUES.md)
- 프로토타입: `prototype/project/TravelTrace.dc.html`

## 빌드

```bash
cd android
sh gradlew assembleDebug
sh gradlew :app:testDebugUnitTest
```

API 키는 `local.properties` → `BuildConfig` 로만 주입한다(하드코딩 금지). 키가 없어도
빌드와 단위 테스트는 통과하며, 지도 타일과 AI 인식만 동작하지 않는다.

## 알아 둘 한계

### 오프라인 재생은 "오프라인 지도"가 아니다

저장된 여행은 **AI 호출 0회**로 다시 열린다 — 좌표·촬영 시각·장소 이름이 전부 기기 안
Room DB 에 있기 때문이다. 하지만 **지도 타일은 2D·3D 모두 네트워크가 필요하다.** 연결이
없으면 경로 폴리라인과 핀은 그대로 그려지지만 지도 배경은 비어 있고, 앱은 그 사실을
지도 화면 상단 배너로 안내한다.

### 분석 캐시는 이 기기에서만 유효하다

같은 사진을 다시 분석하면 캐시(`MediaStore _ID` + 콘텐츠 해시)가 hit 되어 재분석·재호출이
일어나지 않는다. 여행이 달라도 hit 되고, 여행을 삭제해도 캐시는 남는다.

다만 키의 절반인 `MediaStore _ID` 는 **기기 안에서만 안정적**이다. 기기를 바꾸거나 초기화한
뒤에는 같은 사진이라도 캐시가 miss 되어 다시 분석된다 — 버그가 아니라 설계상 정상이다.

콘텐츠 해시는 파일의 **앞 64KiB 와 파일 크기**로 계산한다(전체 바이트가 아니다). 그래야
"해시 → 캐시 조회 → miss 일 때만 실제 판독" 순서가 성립해 hit 이 실제로 일을 줄인다.
자세한 근거는 `data/media/ContentHasher` 자바독 참고.
```

> 위 블록 안의 세 겹 백틱 코드펜스는 README 에 그대로 들어가야 한다 — 이 계획 문서에서
> 복사할 때 중첩 펜스가 깨지지 않도록 주의한다.

- [ ] **Step 2: plan/04 에 해시 결정을 역기록한다**

`plan/04-data-layer.md` 의 "리스크·주의" 첫 항목

```
- 콘텐츠 해시 알고리즘/범위(전체 바이트 vs 앞부분+크기)는 성능·충돌 트레이드오프 — 07과 함께 확정. 결정은 여기 문서에 역기록.
```

을 아래로 교체한다:

```
- **[확정 · S8]** 콘텐츠 해시 = `SHA-256(앞 64KiB ‖ 읽은 프리픽스 길이 ‖ MediaStore SIZE)`.
  전체 바이트를 택하지 않은 이유는 순서 때문이다 — 전체 해시는 EXIF 파싱과 같은 스트림에서
  계산해야 중복 I/O 를 피할 수 있고, 그러면 캐시 조회가 파싱 뒤로 밀려 "hit 이면 파싱을
  건너뛴다"가 불가능해진다. 앞부분+크기는 조회를 판독 앞에 둘 수 있어 hit 이 실제로 일을
  줄인다. 구현: `data/media/ContentHasher`.
- **[확정 · S8]** 해시는 `MediaStore.setRequireOriginal()` 이 아닌 평범한 `content://` URI 에서
  읽는다 — 원본 URI 가 주는 바이트는 `ACCESS_MEDIA_LOCATION` 승인 여부에 따라 달라져 캐시
  키가 권한 상태에 따라 흔들린다. EXIF 판독만 원본 경로를 쓴다.
- **[확정 · S8]** `source == GPS` 인 결과만 캐시한다. `UNKNOWN` 은 "위치가 없다"가 아니라
  "아직 AI 를 안 돌렸다"는 뜻이라, 캐시하면 S3 이 붙어도 그 사진은 영원히 AI 로 못 간다.
  가드는 `RoomAnalysisCacheStore.put()` 안에 있다.
```

- [ ] **Step 3: plan/15 의 상태와 체크박스를 갱신한다**

`plan/15-trip-persistence-offline.md` 의 상태 줄

```
> **상태: ⬜ 미착수.**
```

을 아래로 교체한다:

```
> **상태: 🟡 대부분 완료.** 저장·목록·열기·삭제·빈 상태는 S1 이, 캐시 재사용과 오프라인
> 한계 안내는 S8 이 구현했다. 남은 것은 **여행 이름 입력 UX**(현재는 "YYYY년 M월 여행"
> 자동 명명) 하나다.
```

"캐싱 재사용" 절과 "오프라인 재생" 절의 체크박스 `- [ ]` 를 `- [x]` 로 바꾸고, "여행 저장·홈"
절에서는 첫 항목의 "이름 입력 UX(기본값 ... 제안)"만 `- [ ]` 로 남기고 나머지를 `- [x]` 로
바꾼다. "완료 조건(DoD)" 은 이름 입력 관련을 제외하고 전부 `- [x]`.

- [ ] **Step 4: plan/ISSUES.md 의 진행 상태를 갱신한다**

`plan/ISSUES.md` 상단 "진행" 줄

```
> **진행**: **S0 ✅ 완료** (에픽 01·02 완료, 03은 인자 계약·공유 VM 2항목 미완 — S1에서 함께 처리).
> **다음은 S1.** 에픽별 상세 상태는 [00-overview.md](00-overview.md) 참조.
```

를 아래로 교체한다:

```
> **진행**: **S0 ✅ · S1 ✅ · S8 ✅ 완료.**
> **다음은 S2**(리플레이 — 제품의 핵심 가치) 또는 **S3**(AI 근사 위치). 둘 다 S1 만 선행한다.
> 에픽별 상세 상태는 [00-overview.md](00-overview.md) 참조.
```

`## S8` 섹션 제목을 `## S8 ✅ — 저장 여행 오프라인 재생 + 캐시 재사용` 으로 바꾸고, "Acceptance criteria" 아래에 다음을 덧붙인다:

```
> **구현 노트(S8 완료 시점):** AI 경로가 아직 없으므로 "AI 호출 0회"는 `AnalysisCostLog`
> 카운터로 어서션한다 — S3 이 실제 호출을 붙여도 같은 테스트가 그대로 회귀 그물이 된다.
> 캐시는 `source == GPS` 결과만 담는다(UNKNOWN 을 담으면 S3 이 그 사진을 영영 AI 로 못
> 보낸다). 오프라인 안내는 토스트가 아니라 상단바 아래 상시 배너다.
```

- [ ] **Step 5: plan/00-overview.md 의 진행 표를 갱신한다**

`plan/00-overview.md` 의 "현재 진행 상태" 표에서 `15 trip-persistence` 행의 상태를 `⬜ 미착수` → `🟡 거의` 로, 비고를 `저장·캐시·오프라인 완료(S1·S8). 여행 이름 입력 UX만 남음` 으로 바꾼다. `04 data-layer` 행도 `⬜ 미착수` → `✅ 완료` 로 바꾸고 비고를 `v2 — trips/photos/photo_locations + analysis_cache` 로 바꾼다.

표 아래의 안내 문단

```
> 후속 작업은 에픽 단위가 아니라 [ISSUES.md](ISSUES.md)의 **수직 슬라이스 S1~S8**로 진행한다.
> S0는 사실상 완료(01·02·03), **다음은 S1** — 04를 세우고 05·06의 로직 절반을 채우고 12에 핀·경로를 얹는다.
```

를 아래로 교체한다:

```
> 후속 작업은 에픽 단위가 아니라 [ISSUES.md](ISSUES.md)의 **수직 슬라이스 S1~S8**로 진행한다.
> S0·S1·S8 완료. **다음은 S2**(2D 리플레이 — 제품 핵심 가치) 또는 **S3**(AI 근사 위치).
```

- [ ] **Step 6: 링크와 표가 깨지지 않았는지 확인한다**

Run: `cd /Users/hyunjun_macbook_pro/Documents/Project/project_traveltrace/.claude/worktrees/writing-plans-s8-360bee && grep -c "S8" plan/ISSUES.md README.md`
Expected: 두 파일 모두 1 이상.

- [ ] **Step 7: 최종 전체 검증**

Run: `cd android && sh gradlew :app:testDebugUnitTest`
Expected: PASS — 실패 0

Run: `cd android && sh gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
cd /Users/hyunjun_macbook_pro/Documents/Project/project_traveltrace/.claude/worktrees/writing-plans-s8-360bee
git add README.md plan/04-data-layer.md plan/15-trip-persistence-offline.md \
  plan/ISSUES.md plan/00-overview.md
git commit -m "docs: record the cache-key decision and the offline limits S8 actually ships"
```

---

## 수용 기준 대조표

| ISSUES.md S8 수용 기준 | 충족 지점 |
|---|---|
| 저장 여행 열기 → AI 호출 0회로 지도·리플레이 재생(비용 로그 확인) | Task 4 `AnalysisCostLog` + Task 5 `AnalysisCacheReuseTest.noVisionOrGeocodeCallEverHappens` + `logSummary` 로 logcat 육안 확인 |
| 같은 사진 재분석 시 캐시 hit(네트워크 0), 다른 여행에서도 hit | Task 3 `RoomAnalysisCacheStoreTest.theSamePhotoHitsFromADifferentTripBecauseTheCacheIsTripIndependent` + Task 5 `reanalyzingTheSamePhotosInANewTripHitsTheCacheAndSkipsExif` |
| 오프라인에서 타일 실패해도 폴리라인 재생 + 한계 안내(크래시 없음) | Task 6 — `MapRouteRenderer.draw` 는 연결과 무관하게 저장된 좌표로 그린다(변경 없음), `ConnectivityMonitor` + `map_offline_notice` 배너가 안내 |
| 캐시 기기 로컬 한정을 README·앱에 명시 (plan/15) | Task 7 README "분석 캐시는 이 기기에서만 유효하다" |
| 해시 결정 역기록 (plan/04) | Task 7 plan/04 "[확정 · S8]" 3항목 |
