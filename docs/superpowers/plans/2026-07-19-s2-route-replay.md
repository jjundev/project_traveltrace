# S2 — 경로 리플레이(자동/수동 슬라이드쇼, 2D 카메라) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S1이 그려 놓은 지도 위에서 재생을 누르면 카메라가 스톱을 시각순으로 순회하며 도착할 때마다 사진 카드가 팝으로 뜨고, 일시정지·이전/다음·타임라인 점프·속도 프리셋·상영 모드가 모두 실제로 동작한다.

**Architecture:** 재생 상태 머신(`ReplayEngine`)을 `MapReplayViewModel` 안으로 넣어 SoT를 하나로 유지하고, 지도 카메라는 `CameraAnimator` 인터페이스 뒤에 숨겨 Maps SDK 없이 단위 테스트한다. Fragment는 지도가 준비되면 `GoogleMapCameraAnimator`를 VM에 꽂고(attach) 뷰가 죽으면 뽑는(detach) 역할만 한다 — 여전히 상태를 렌더할 뿐 재생 로직을 갖지 않는다. `MapUiState`의 필드 구성은 `contentUri` 하나만 늘고 나머지는 그대로다.

**Tech Stack:** Java 17, Android View/XML, Fragment + Navigation Component + ViewBinding, Hilt(annotationProcessor), Room(annotationProcessor), Glide, Google Maps SDK, `Handler`/`ViewPropertyAnimator`, Robolectric + Roborazzi.

## Global Constraints

- **Java + View/XML만.** Kotlin·Compose·코루틴·KSP·kotlinx.serialization 도입 금지.
- 비동기는 **`ExecutorService`** 고정(재생 타이머는 UI 타이밍이라 main `Handler`). JSON은 Gson. DI/Room은 **annotationProcessor**.
- Gradle은 **Groovy DSL**, buildSrc는 Java.
- `compileSdk 35 / targetSdk 35 / minSdk 33`.
- 사용자 대면 문자열은 전부 **`res/values/strings.xml`의 한국어 리소스**. 코드에 한국어 리터럴 금지(자바독·주석은 한국어 허용).
- **`MapUiState.Stop` 생성자를 바꾸면 호출부를 열거하지 말고 찾아낸다.** 반드시 먼저 실행한다:
  `grep -rn "new MapUiState.Stop(" android/app/src`
  그리고 나온 **모든** 줄을 고친다. S1 grill-review 에서 이 누락으로 Blocker 4건이 났고, 이 계획의 초안도 고정 목록을 쓰다가 `MapRouteRendererTest` 를 빠뜨렸다 — 목록은 또 드리프트한다. 함께 확인할 것: 하드코딩 문자열을 어서션하는 Renderer 테스트와 골든 스크린샷.
  > Gradle 은 `--tests` 필터와 무관하게 **테스트 소스셋 전체를 컴파일한다.** 호출부를 하나라도 빠뜨리면 무관해 보이는 테스트가 컴파일 에러로 죽는다.
- **기존 단위 테스트 166개는 회귀 0건.** 골든 스크린샷 14장은 **전부 불변** — 이 슬라이스는 새 뷰를 전부 `visibility="gone"` 기본값으로 넣고 픽스처 경로에서 그 상태가 유지되므로 픽셀이 바뀌지 않는다. 골든이 깨지면 그건 버그 신호지 재기록 신호가 아니다.
- 좌표 없는 사진을 **절대 (0,0)에 찍지 않는다.** 좌표가 없으면 null 로 두거나 걸러낸다 — `0d` 로 coalesce 하지 않는다.
- **프로토타입의 rAF 카메라 코드는 이식하지 않는다.** 프로토타입 `startFlight`의 CSS transform/tilt/rotate 합성은 가짜 지도용이다. 네이티브에선 Maps 카메라 애니메이션의 중단·재개 API로 등가 구현한다(plan/13 리스크 항목).
- **detach(핀 빼기)·위치 미상 drawer 실데이터·3D 비행은 이 슬라이스 범위 밖이다** — 각각 S6·S6·S7. `detachButton`은 지금처럼 토스트만 띄운 채로 둔다.
- 기존 파일을 고치라는 지시의 **줄 번호는 근사치**다. 앞선 태스크가 같은 파일에 줄을 넣으면 밀린다 — 항상 **함께 적힌 코드 내용으로 위치를 찾는다.**

---

## File Structure

**신규 (`android/app/src/main/java/com/traveltrace/app/ui/map/`)**

| 경로 | 책임 |
|---|---|
| `ReplayPlan.java` | 속도·거리 → dwell/비행 시간/휴지 줌 계산 + haversine. 순수 정적 유틸 |
| `ReplayScheduler.java` | dwell 타이머 seam (`postDelayed`/`cancelAll`) |
| `MainThreadReplayScheduler.java` | main `Handler` + 토큰 기반 일괄 취소 구현 |
| `CameraAnimator.java` | 지도 카메라 seam — 위경도·줌·시간만 말하고 Maps 타입을 노출하지 않는다 |
| `GoogleMapCameraAnimator.java` | `CameraAnimator` → `GoogleMap.animateCamera/moveCamera/stopAnimation` 얇은 어댑터 |
| `ReplayCamera.java` | 비행 1건의 생명주기 — 공중 정지(freeze)와 잔여 구간 재개(resume) |
| `ReplayEngine.java` | 재생 상태 머신 — playing/active/moving, dwell 순회, 이전/다음/점프, 속도·상영 |
| `PhotoCardPop.java` | 도착 시 사진 카드 팝 애니메이션(scale/alpha) |

**신규 (테스트, `android/app/src/test/java/com/traveltrace/app/ui/map/`)**

| 경로 | 책임 |
|---|---|
| `ReplayPlanTest.java` | dwell·비행시간·줌·거리 |
| `FakeCameraAnimator.java` | 테스트용 `CameraAnimator` — 마지막 호출 기록 + 도착/취소 수동 발화 |
| `FakeReplayScheduler.java` | 테스트용 `ReplayScheduler` — 예약된 작업 수동 실행 |
| `ReplayCameraTest.java` | freeze/resume 잔여 시간, 세대(generation) 가드 |
| `ReplayEngineTest.java` | 자동 순회·일시정지/재개·이전/다음/점프·경계·정리 |
| `PhotoCardPopTest.java` | 팝 시작 상태·재무장 |

**수정**

| 경로 | 변경 |
|---|---|
| `ui/map/MapUiState.java` | `Stop`에 `@Nullable Uri contentUri` 추가 |
| `ui/map/MapRenderer.java` | 배너·상영 카드에 Glide 썸네일 바인딩 |
| `ui/map/MapReplayViewModel.java` | `ReplayEngine` 위임으로 재작성 + `attachCamera`/`detachCamera`/`pausePlayback` |
| `ui/map/MapReplayFragment.java` | 애니메이터 attach/detach, `onPause` 정지, 도착 팝 |
| `ui/preview/ScreenFixtures.java` | `map()`의 `Stop` 6건에 `null` uri 인자 추가 |
| `res/layout/view_map_bottom_sheet.xml` | 배너에 `photoImage` ImageView 추가 |
| `res/layout/view_cinema_overlay.xml` | 상영 카드에 `cinemaImage` ImageView 추가 |
| `res/values/strings.xml` | `map_photo_desc` 추가 |
| `test/.../MapReplayFragmentRouteGuardTest.java` | `stop()` 헬퍼에 uri 인자 추가 |
| `test/.../MapReplayViewModelTest.java` | 엔진 위임 후에도 통과하도록 확인(수정 불필요 예상) |

---

## Task 1: 사진 썸네일 — 하단시트 배너 · 상영 카드

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java`
- Modify: `android/app/src/main/res/layout/view_map_bottom_sheet.xml`
- Modify: `android/app/src/main/res/layout/view_cinema_overlay.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayFragmentRouteGuardTest.java`
- Modify: `android/app/src/test/java/com/traveltrace/app/ui/map/MapRouteRendererTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapSheetRendererTest.java` (추가)

**Interfaces:**
- Consumes: 없음 (이 계획의 첫 태스크)
- Produces: `MapUiState.Stop`의 9-인자 생성자
  `Stop(String id, String name, String time, boolean ai, int extra, @ColorInt int toneColor, double lat, double lng, @Nullable android.net.Uri contentUri)`.
  **이후 모든 태스크는 이 9-인자 형태로 `Stop`을 만든다.**

**왜 먼저인가:** 재생 로직 태스크들이 테스트에서 `Stop`을 대량으로 만든다. UiState 변경을 뒤로 미루면 그 헬퍼를 전부 다시 고쳐야 하므로, 시그니처를 바꾸는 이 태스크를 맨 앞에 둔다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapSheetRendererTest.java` 의 클래스 맨 끝(마지막 `}` 바로 위)에 아래 테스트 2개를 추가한다. 파일 상단 import 에 `android.net.Uri` 를 더한다.

```java
    /** 픽스처(contentUri=null)는 실제 사진이 없다 — 톤 색만 남기고 ImageView 는 숨긴다. */
    @Test
    public void stopWithoutContentUri_hidesPhotoImage() {
        MapRenderer.renderSheet(binding, at(0));

        assertEquals(View.GONE, binding.photoImage.getVisibility());
    }

    /** 실제 사진이 있으면 ImageView 를 띄운다(비트맵 로딩은 Glide 비동기라 여기서 보지 않는다). */
    @Test
    public void stopWithContentUri_showsPhotoImage() {
        MapUiState base = ScreenFixtures.map();
        MapUiState.Stop origin = base.stops.get(0);
        MapUiState.Stop withPhoto = new MapUiState.Stop(
                origin.id, origin.name, origin.time, origin.ai, origin.extra,
                origin.toneColor, origin.lat, origin.lng,
                Uri.parse("content://media/external/images/media/42"));
        MapUiState state = new MapUiState(base.tripTitle, base.unknownCount,
                Collections.singletonList(withPhoto), 0,
                base.playing, base.satellite, base.cinema, base.speed);

        MapRenderer.renderSheet(binding, state);

        assertEquals(View.VISIBLE, binding.photoImage.getVisibility());
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapSheetRendererTest'`
Expected: 컴파일 실패 — `cannot find symbol: variable photoImage` 그리고 `constructor Stop ... cannot be applied to given types`.

- [ ] **Step 3: `MapUiState.Stop` 에 `contentUri` 를 추가한다**

`MapUiState.java` 상단 import 에 아래 두 줄을 더한다(기존 `androidx.annotation.ColorInt` 아래).

```java
import android.net.Uri;

import androidx.annotation.Nullable;
```

`Stop` 클래스의 `lng` 필드 선언 바로 아래에 필드를 넣고, 생성자를 교체한다.

```java
        /** 지도에 찍을 좌표. PLACED 인 스톱만 여기 오므로 항상 유효하다. */
        public final double lat;
        public final double lng;
        /**
         * 이 정차 지점 대표 사진의 MediaStore content URI. 픽스처·프리뷰 경로에선 null 이라
         * 톤 색만 남는다 (PhotoGridAdapter 의 썸네일 규칙과 동일).
         */
        @Nullable public final Uri contentUri;

        public Stop(String id, String name, String time, boolean ai, int extra,
                    @ColorInt int toneColor, double lat, double lng,
                    @Nullable Uri contentUri) {
            this.id = id;
            this.name = name;
            this.time = time;
            this.ai = ai;
            this.extra = extra;
            this.toneColor = toneColor;
            this.lat = lat;
            this.lng = lng;
            this.contentUri = contentUri;
        }
```

- [ ] **Step 4: 레이아웃에 ImageView 두 개를 넣는다**

`res/layout/view_map_bottom_sheet.xml` — `@+id/photoTone` View 바로 **아래**(스크림 View 위)에 삽입한다.

```xml
        <ImageView
            android:id="@+id/photoImage"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@string/map_photo_desc"
            android:scaleType="centerCrop"
            android:visibility="gone" />
```

`res/layout/view_cinema_overlay.xml` — `@+id/cinemaTone` View 바로 **아래**(스크림 View 위)에 삽입한다.

```xml
        <ImageView
            android:id="@+id/cinemaImage"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@string/map_photo_desc"
            android:scaleType="centerCrop"
            android:visibility="gone" />
```

`res/values/strings.xml` — `<!-- MAP · 하단 시트 -->` 블록의 `map_detach_toast` 줄 아래에 추가한다.

```xml
    <string name="map_photo_desc">정차 지점 사진</string>
```

- [ ] **Step 5: `MapRenderer` 가 썸네일을 바인딩한다**

`MapRenderer.java` 상단 import 에 아래를 더한다.

```java
import android.net.Uri;
import android.widget.ImageView;

import androidx.annotation.Nullable;
```

`renderSheet` 안, `binding.photoTone.setBackgroundColor(stop.toneColor);` 바로 아래에 한 줄을 넣는다.

```java
        binding.photoTone.setBackgroundColor(stop.toneColor);
        bindPhoto(binding.photoImage, stop.contentUri);
```

`renderCinema` 안, `binding.cinemaTone.setBackground(tone);` 바로 아래에 한 줄을 넣는다.

```java
        binding.cinemaTone.setBackground(tone);
        bindPhoto(binding.cinemaImage, stop.contentUri);
```

클래스 맨 끝(마지막 `}` 바로 위)에 헬퍼를 추가한다.

```java
    /**
     * 실제 사진이 있으면 톤 색 위를 썸네일로 덮고, 없으면(픽스처·프리뷰) 톤 색만 남긴다 —
     * {@code PhotoGridAdapter} 와 같은 규칙이다.
     *
     * <p>null 경로에서도 {@code Glide.clear()} 를 반드시 부른다: 사진 있는 스톱 → 없는 스톱으로
     * 넘어갈 때 앞선 로딩이 뒤늦게 완료되면 숨겨 놓은 ImageView 에 이전 사진이 다시 꽂힌다.
     */
    private static void bindPhoto(ImageView view, @Nullable Uri uri) {
        if (uri == null) {
            com.bumptech.glide.Glide.with(view).clear(view);
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        com.bumptech.glide.Glide.with(view)
                .load(uri)
                .centerCrop()
                .into(view);
    }
```

- [ ] **Step 6: `Stop` 을 만드는 기존 호출부를 전부 고친다**

먼저 호출부를 직접 찾는다 — 아래 목록을 믿지 말고 grep 결과를 믿는다.

Run: `cd android && grep -rn "new MapUiState.Stop(" app/src`
Expected: 9줄 — `ScreenFixtures.java` 6줄, `MapReplayViewModel.java` 1줄, `MapReplayFragmentRouteGuardTest.java` 1줄, `MapRouteRendererTest.java` 1줄. **더 나오면 그것도 전부 고친다.**

`ui/preview/ScreenFixtures.java` 의 `map()` — 6개 `Stop` 생성에 마지막 인자 `null` 을 붙인다.

```java
    public static MapUiState map() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8,
                48.8738, 2.2950, null));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 4, 0xFFB7C6D6,
                48.8584, 2.2945, null));
        stops.add(new MapUiState.Stop("seine", "센강 유람선", "13:20", false, 2, 0xFFA9C6DA,
                48.8600, 2.3050, null));
        stops.add(new MapUiState.Stop("louvre", "루브르 박물관", "15:40", true, 0, 0xFFCDBFA1,
                48.8606, 2.3376, null));
        stops.add(new MapUiState.Stop("notredame", "노트르담", "16:50", false, 0, 0xFFC3B69B,
                48.8530, 2.3499, null));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 3, 0xFFD7D0BF,
                48.8867, 2.3431, null));
        return new MapUiState("2024 파리 여행", 5, stops, 0, false, false, false,
                MapUiState.Speed.NORMAL);
    }
```

`test/.../MapReplayFragmentRouteGuardTest.java` 와 `test/.../MapRouteRendererTest.java` 의 `stop()` 헬퍼 — 두 파일에 **똑같은 이름의 별개 헬퍼**가 있다. 둘 다 아래로 고친다.

```java
    private static MapUiState.Stop stop(String id, double lat, double lng) {
        return new MapUiState.Stop(id, id, "10:00", false, 0, 0xFFCCCCCC, lat, lng, null);
    }
```

> `MapRouteRendererTest` 는 이 슬라이스와 아무 상관 없어 보이지만, Gradle 은 `--tests` 필터와 무관하게 테스트 소스셋 전체를 컴파일하므로 여기를 빠뜨리면 Step 7 이 컴파일 에러로 죽는다.

`ui/map/MapReplayViewModel.java` 의 `toState()` 안 `stops.add(...)` — 마지막 인자로 MediaStore content URI 를 만든다. 파일 상단 import 에 `android.content.ContentUris` 와 `android.provider.MediaStore` 를 더한다.

```java
            stops.add(new MapUiState.Stop(
                    row.photoId,
                    row.landmarkName != null ? row.landmarkName : row.displayName,
                    row.takenAtUtc == null ? "" : fmt.format(new Date(row.takenAtUtc)),
                    row.source == LocationSource.AI,
                    0,
                    TONES[i % TONES.length],
                    row.lat,
                    row.lng,
                    ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId)));
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapSheetRendererTest'`
Expected: PASS (기존 테스트 포함 전부).

- [ ] **Step 8: 골든 스크린샷과 전체 회귀를 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 0건. 골든 14장은 verify 모드로 전부 통과해야 한다 — 픽스처 `contentUri` 가 null 이라 새 ImageView 는 항상 `GONE` 이고 픽셀이 변하지 않는다.

> 만약 `Glide.with(...)` 가 Robolectric 에서 던지면(선례상 `TripCardAdapter` 가 `home_trips.png` 골든에서 이미 같은 호출을 하므로 통과해야 정상이다) 그건 이 태스크의 회귀다. 우회하지 말고 실패 스택을 그대로 보고하고 멈춘다.

- [ ] **Step 9: 커밋**

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat: show real photo thumbnails on the map replay card"
```

---

## Task 2: `ReplayPlan` — 속도·거리 → dwell/비행시간/줌

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayPlan.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayPlanTest.java`

**Interfaces:**
- Consumes: `MapUiState.Speed` (`RELAXED`/`NORMAL`/`FAST`)
- Produces:
  - `static long ReplayPlan.dwellMs(MapUiState.Speed speed)`
  - `static long ReplayPlan.flightMs(MapUiState.Speed speed, double meters)`
  - `static float ReplayPlan.restZoom(boolean cinema)`
  - `static double ReplayPlan.distanceMeters(double lat1, double lng1, double lat2, double lng2)`
  - 상수 `DWELL_RELAXED_MS=2800`, `DWELL_NORMAL_MS=1800`, `DWELL_FAST_MS=1100`, `FLIGHT_MIN_MS=1200`, `REST_ZOOM=14f`, `REST_ZOOM_CINEMA=15f`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayPlanTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 리플레이 타이밍은 제품 감각을 좌우하는 숫자라 전부 여기서 못 박는다.
 * dwell 값은 plan/ISSUES.md S2 가 프로토타입 결정으로 확정한 값이다.
 */
public class ReplayPlanTest {

    @Test
    public void dwellFollowsTheSpeedPreset() {
        assertEquals(2800L, ReplayPlan.dwellMs(MapUiState.Speed.RELAXED));
        assertEquals(1800L, ReplayPlan.dwellMs(MapUiState.Speed.NORMAL));
        assertEquals(1100L, ReplayPlan.dwellMs(MapUiState.Speed.FAST));
    }

    @Test
    public void flightGrowsWithDistanceUpToTheFarThreshold() {
        long near = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 0d);
        long mid = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 2_500d);
        long far = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 5_000d);
        long beyond = ReplayPlan.flightMs(MapUiState.Speed.NORMAL, 50_000d);

        assertTrue("가까울수록 짧아야 한다", near < mid);
        assertTrue("멀수록 길어야 한다", mid < far);
        assertEquals("상한을 넘으면 더 길어지지 않는다", far, beyond);
    }

    @Test
    public void flightNeverDropsBelowTheFloor() {
        // fast × 거리 0 이면 1300*0.7 = 910ms 라 바닥값이 걸린다 — 눈이 못 따라가는 이동을 막는다.
        assertEquals(ReplayPlan.FLIGHT_MIN_MS, ReplayPlan.flightMs(MapUiState.Speed.FAST, 0d));
    }

    @Test
    public void fasterPresetFliesShorterAtTheSameDistance() {
        double d = 3_000d;
        assertTrue(ReplayPlan.flightMs(MapUiState.Speed.FAST, d)
                < ReplayPlan.flightMs(MapUiState.Speed.NORMAL, d));
        assertTrue(ReplayPlan.flightMs(MapUiState.Speed.NORMAL, d)
                < ReplayPlan.flightMs(MapUiState.Speed.RELAXED, d));
    }

    @Test
    public void cinemaRestsCloser() {
        assertTrue("상영 모드는 사진에 더 붙어야 한다",
                ReplayPlan.restZoom(true) > ReplayPlan.restZoom(false));
    }

    @Test
    public void distanceMatchesKnownParisLandmarks() {
        // 개선문(48.8738, 2.2950) ↔ 에펠탑(48.8584, 2.2945) 은 실제로 약 1.7km 다.
        double meters = ReplayPlan.distanceMeters(48.8738, 2.2950, 48.8584, 2.2945);

        assertEquals(1_715d, meters, 60d);
    }

    @Test
    public void distanceOfTheSamePointIsZero() {
        assertEquals(0d, ReplayPlan.distanceMeters(48.86, 2.29, 48.86, 2.29), 0.001d);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayPlanTest'`
Expected: 컴파일 실패 — `cannot find symbol: class ReplayPlan`.

- [ ] **Step 3: `ReplayPlan` 을 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayPlan.java`:

```java
package com.traveltrace.app.ui.map;

/**
 * 리플레이 타이밍·줌 계산. Android·Maps 타입에 전혀 의존하지 않는 순수 함수라
 * 숫자 감각을 단위 테스트로 못 박을 수 있다.
 *
 * <p>프로토타입(prototype/TravelTrace.html)의 {@code viewDwell()}/{@code startFlight()} 가
 * 쓰던 값을 옮겨 왔다. 다만 프로토타입의 거리는 가짜 지도의 % 좌표였으므로, 여기선 실제
 * 미터로 바꾸고 "먼 이동" 기준을 {@link #FAR_METERS} 로 다시 잡았다. tilt/rotate 합성은
 * CSS 3D 전용이라 이식하지 않는다 — 2D 팬/줌만 쓴다(plan/13).
 */
public final class ReplayPlan {

    /** 도착 후 다음 스톱으로 떠나기까지 머무는 시간 (plan/ISSUES.md S2 확정값). */
    public static final long DWELL_RELAXED_MS = 2800L;
    public static final long DWELL_NORMAL_MS = 1800L;
    public static final long DWELL_FAST_MS = 1100L;

    /** 거리 0 일 때의 기준 비행 시간. 실제 시간은 거리에 따라 이 값을 늘린다. */
    private static final long FLIGHT_BASE_RELAXED_MS = 2600L;
    private static final long FLIGHT_BASE_NORMAL_MS = 1900L;
    private static final long FLIGHT_BASE_FAST_MS = 1300L;

    /** 아무리 짧아도 이보다 빠르면 눈이 이동을 못 따라간다. */
    public static final long FLIGHT_MIN_MS = 1200L;

    /** 이 거리 이상은 전부 "먼 이동"으로 같게 취급한다 — 도시 간 이동에서 시간이 폭주하지 않게. */
    private static final double FAR_METERS = 5_000d;

    /** 스톱에 멈춰 있을 때의 줌. 상영 모드는 사진에 더 붙는다(프로토타입 zBase 2.0→2.4 의도). */
    public static final float REST_ZOOM = 14f;
    public static final float REST_ZOOM_CINEMA = 15f;

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private ReplayPlan() {}

    public static long dwellMs(MapUiState.Speed speed) {
        switch (speed) {
            case RELAXED: return DWELL_RELAXED_MS;
            case FAST: return DWELL_FAST_MS;
            default: return DWELL_NORMAL_MS;
        }
    }

    private static long flightBaseMs(MapUiState.Speed speed) {
        switch (speed) {
            case RELAXED: return FLIGHT_BASE_RELAXED_MS;
            case FAST: return FLIGHT_BASE_FAST_MS;
            default: return FLIGHT_BASE_NORMAL_MS;
        }
    }

    /** 기준시간 × (0.7 ~ 1.2), 거리 비례. 바닥은 {@link #FLIGHT_MIN_MS}. */
    public static long flightMs(MapUiState.Speed speed, double meters) {
        double k = Math.min(Math.max(meters, 0d) / FAR_METERS, 1d);
        long raw = Math.round(flightBaseMs(speed) * (0.7d + k * 0.5d));
        return Math.max(FLIGHT_MIN_MS, raw);
    }

    public static float restZoom(boolean cinema) {
        return cinema ? REST_ZOOM_CINEMA : REST_ZOOM;
    }

    /**
     * 두 좌표 사이 대권 거리(m). android-maps-utils 를 끌어오지 않으려고 직접 구현한다
     * (프로토타입 "자체 구현" 결정과 같은 이유).
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1d, Math.sqrt(a)));
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayPlanTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map/ReplayPlan.java android/app/src/test/java/com/traveltrace/app/ui/map/ReplayPlanTest.java
git commit -m "feat: add replay timing and zoom math"
```

---

## Task 3: 카메라 seam + `ReplayCamera` (공중 정지·재개)

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/CameraAnimator.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/GoogleMapCameraAnimator.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayCamera.java`
- Create: `android/app/src/test/java/com/traveltrace/app/ui/map/FakeCameraAnimator.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayCameraTest.java`

**Interfaces:**
- Consumes: `ReplayPlan` (없어도 되지만 같은 패키지)
- Produces:
  - `interface CameraAnimator { interface Listener { void onArrive(); void onCancel(); } void animateTo(double lat, double lng, float zoom, long durationMs, Listener listener); void moveTo(double lat, double lng, float zoom); void stop(); }`
  - `class GoogleMapCameraAnimator implements CameraAnimator` — 생성자 `GoogleMapCameraAnimator(GoogleMap map)`
  - `class ReplayCamera` — 생성자 `ReplayCamera(CameraAnimator animator)` (테스트용 package-private `ReplayCamera(CameraAnimator, LongSupplier clock)`), 메서드
    `void flyTo(double lat, double lng, float zoom, long durationMs, ReplayCamera.Arrival cb)`,
    `boolean freeze()`, `boolean resume()`, `boolean isFrozen()`, `long remainingMs()`,
    `void cancel()`, `void moveTo(double lat, double lng, float zoom)`
  - `interface ReplayCamera.Arrival { void onArrive(); void onInterrupted(); }`
  - 테스트 더블 `FakeCameraAnimator` — 필드 `lastLat/lastLng/lastZoom/lastDurationMs`, `int animateCount`, `int stopCount`, 메서드 `void arrive()`, `void cancel()`

- [ ] **Step 1: 테스트용 `FakeCameraAnimator` 를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/ui/map/FakeCameraAnimator.java`:

```java
package com.traveltrace.app.ui.map;

/**
 * 지도 없이 카메라 동작을 검증하기 위한 테스트 더블. 도착/취소를 테스트가 직접 발화한다
 * — 실제 {@code GoogleMap} 은 콜백을 다음 프레임에 비동기로 주므로 그 타이밍도 흉내 낼 수 있다.
 */
public class FakeCameraAnimator implements CameraAnimator {

    public double lastLat;
    public double lastLng;
    public float lastZoom;
    public long lastDurationMs;
    public int animateCount;
    public int moveCount;
    public int stopCount;

    private Listener listener;

    @Override
    public void animateTo(double lat, double lng, float zoom, long durationMs, Listener l) {
        lastLat = lat;
        lastLng = lng;
        lastZoom = zoom;
        lastDurationMs = durationMs;
        animateCount++;
        listener = l;
    }

    @Override
    public void moveTo(double lat, double lng, float zoom) {
        lastLat = lat;
        lastLng = lng;
        lastZoom = zoom;
        moveCount++;
    }

    @Override
    public void stop() {
        stopCount++;
    }

    /** 진행 중인 애니메이션이 목적지에 닿았다고 알린다. */
    public void arrive() {
        if (listener != null) listener.onArrive();
    }

    /** 애니메이션이 중단됐다고 알린다 (사용자 제스처·stopAnimation 양쪽 모두 이 경로다). */
    public void cancel() {
        if (listener != null) listener.onCancel();
    }
}
```

Create `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayCameraTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 공중 정지(freeze)와 잔여 구간 재개(resume)가 이 슬라이스의 수용 기준 중 하나다
 * ("일시정지→재개가 이동 중에도 자연스럽게 잔여 구간을 이어감").
 */
public class ReplayCameraTest {

    private FakeCameraAnimator animator;
    private AtomicLong now;
    private ReplayCamera camera;
    private AtomicInteger arrivals;
    private AtomicInteger interruptions;
    private ReplayCamera.Arrival probe;

    @Before
    public void setUp() {
        animator = new FakeCameraAnimator();
        now = new AtomicLong(1_000L);
        camera = new ReplayCamera(animator, now::get);
        arrivals = new AtomicInteger();
        interruptions = new AtomicInteger();
        probe = new ReplayCamera.Arrival() {
            @Override public void onArrive() { arrivals.incrementAndGet(); }
            @Override public void onInterrupted() { interruptions.incrementAndGet(); }
        };
    }

    @Test
    public void flyToStartsAnAnimationWithTheGivenDuration() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        assertEquals(1, animator.animateCount);
        assertEquals(48.86, animator.lastLat, 0.0001d);
        assertEquals(2_000L, animator.lastDurationMs);
    }

    @Test
    public void arrivalReportsOnceAndEndsTheFlight() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        animator.arrive();

        assertEquals(1, arrivals.get());
        assertFalse(camera.isFrozen());
        assertFalse("도착 후에는 얼릴 비행이 없다", camera.freeze());
    }

    @Test
    public void freezeStopsTheAnimationAndKeepsTheRemainder() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L); // 800ms 경과

        assertTrue(camera.freeze());

        assertEquals(1, animator.stopCount);
        assertTrue(camera.isFrozen());
        assertEquals(1_200L, camera.remainingMs());
    }

    @Test
    public void resumeFliesOnlyTheRemainingSegment() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L);
        camera.freeze();

        assertTrue(camera.resume());

        assertEquals("재개는 새 애니메이션 1건", 2, animator.animateCount);
        assertEquals("남은 구간만 이어간다", 1_200L, animator.lastDurationMs);
        assertEquals("목적지는 그대로", 48.86, animator.lastLat, 0.0001d);
        assertFalse(camera.isFrozen());
    }

    @Test
    public void resumeThenArriveStillReportsTheOriginalArrival() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L);
        camera.freeze();
        camera.resume();

        animator.arrive();

        assertEquals(1, arrivals.get());
        assertEquals(0, interruptions.get());
    }

    @Test
    public void freezeThenResumeTwiceKeepsShrinkingTheRemainder() {
        camera.flyTo(48.86, 2.29, 14f, 3_000L, probe);
        now.set(2_000L); // 1000ms 경과
        camera.freeze();
        now.set(5_000L); // 정지 중 흐른 시간은 세지 않는다
        camera.resume();
        now.set(5_500L); // 다시 500ms 비행
        camera.freeze();

        assertEquals(1_500L, camera.remainingMs());
    }

    @Test
    public void lateCancelAfterOurOwnFreezeIsNotReportedAsInterruption() {
        // 실제 GoogleMap 은 stopAnimation() 뒤 다음 프레임에 onCancel 을 준다 —
        // 우리가 스스로 멈춘 것을 "사용자가 방해했다"로 오인하면 재생이 죽는다.
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        camera.freeze();

        animator.cancel();

        assertEquals(0, interruptions.get());
        assertTrue("여전히 재개 가능해야 한다", camera.isFrozen());
    }

    @Test
    public void externalCancelDuringFlightIsReportedAsInterruption() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        animator.cancel(); // 사용자가 지도를 만져 애니메이션이 끊긴 경우

        assertEquals(1, interruptions.get());
        assertEquals(0, arrivals.get());
    }

    @Test
    public void cancelSilencesTheCallbackEntirely() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        camera.cancel();
        animator.arrive();
        animator.cancel();

        assertEquals(0, arrivals.get());
        assertEquals(0, interruptions.get());
    }

    @Test
    public void moveToCancelsAnyFlightAndJumpsInstantly() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        camera.moveTo(48.88, 2.34, 15f);
        animator.arrive();

        assertEquals(1, animator.moveCount);
        assertEquals(48.88, animator.lastLat, 0.0001d);
        assertEquals(0, arrivals.get());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayCameraTest'`
Expected: 컴파일 실패 — `cannot find symbol: class CameraAnimator` / `class ReplayCamera`.

- [ ] **Step 3: `CameraAnimator` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/CameraAnimator.java`:

```java
package com.traveltrace.app.ui.map;

/**
 * 지도 카메라를 움직이는 최소 계약. Maps SDK 타입(CameraUpdate·LatLng·GoogleMap)을
 * 일부러 노출하지 않는다 — 그 타입들은 단위 테스트 환경에서 초기화되지 않아
 * 재생 로직 전체를 테스트 불가능하게 만든다. 여기 남는 건 위경도·줌·시간뿐이다.
 */
public interface CameraAnimator {

    interface Listener {
        /** 목적지에 정상 도착. */
        void onArrive();

        /** 도착 전에 애니메이션이 끊겼다 (다른 애니메이션·정지 요청·사용자 제스처). */
        void onCancel();
    }

    void animateTo(double lat, double lng, float zoom, long durationMs, Listener listener);

    /** 애니메이션 없이 즉시 이동. */
    void moveTo(double lat, double lng, float zoom);

    /** 진행 중 애니메이션을 현재 위치에서 멈춘다. 콜백은 onCancel 로 온다. */
    void stop();
}
```

- [ ] **Step 4: `GoogleMapCameraAnimator` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/GoogleMapCameraAnimator.java`:

```java
package com.traveltrace.app.ui.map;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

/**
 * {@link CameraAnimator} → 표준 Maps SDK. 로직이 하나도 없는 어댑터로 유지한다 —
 * 여기 조건문이 생기면 그건 {@link ReplayCamera}·{@link ReplayEngine} 로 가야 할 로직이다.
 */
public final class GoogleMapCameraAnimator implements CameraAnimator {

    private final GoogleMap map;

    public GoogleMapCameraAnimator(GoogleMap map) {
        this.map = map;
    }

    @Override
    public void animateTo(double lat, double lng, float zoom, long durationMs, Listener listener) {
        int duration = (int) Math.max(1L, Math.min(durationMs, Integer.MAX_VALUE));
        map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom),
                duration,
                new GoogleMap.CancelableCallback() {
                    @Override
                    public void onFinish() {
                        listener.onArrive();
                    }

                    @Override
                    public void onCancel() {
                        listener.onCancel();
                    }
                });
    }

    @Override
    public void moveTo(double lat, double lng, float zoom) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom));
    }

    @Override
    public void stop() {
        map.stopAnimation();
    }
}
```

- [ ] **Step 5: `ReplayCamera` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayCamera.java`:

```java
package com.traveltrace.app.ui.map;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.function.LongSupplier;

/**
 * 비행 1건의 생명주기. 프로토타입 {@code freezeFlight}/{@code resumeFlight} 의 네이티브 등가물이다
 * — rAF 로 프레임을 직접 굴리는 대신, 진행 중 애니메이션을 멈추고 <em>남은 시간만큼</em>
 * 현재 위치에서 목적지로 다시 애니메이션한다.
 *
 * <p><b>세대(generation) 가드가 핵심이다.</b> 우리가 {@code stop()} 을 부르면 실제
 * {@code GoogleMap} 은 {@code onCancel} 을 <em>다음 프레임에 비동기로</em> 준다. "지금 내가 멈추는
 * 중"이라는 순간 플래그로는 그 늦은 콜백을 걸러낼 수 없어서, 우리가 의도적으로 끊을 때마다
 * 세대를 올리고 옛 세대의 콜백은 전부 버린다. 이게 없으면 일시정지가 "사용자가 방해함"으로
 * 오인되어 재생이 죽는다.
 */
public class ReplayCamera {

    /** 비행 결과. */
    public interface Arrival {
        void onArrive();

        /** 우리가 아닌 무언가가 비행을 끊었다 (사용자 제스처 등). */
        void onInterrupted();
    }

    private final CameraAnimator animator;
    private final LongSupplier clock;

    @Nullable private Arrival arrival;
    private double targetLat;
    private double targetLng;
    private float targetZoom;
    private long totalMs;
    private long elapsedMs;
    private long startedAtMs;
    private boolean inFlight;
    private boolean frozen;
    private int generation;

    public ReplayCamera(CameraAnimator animator) {
        this(animator, SystemClock::uptimeMillis);
    }

    ReplayCamera(CameraAnimator animator, LongSupplier clock) {
        this.animator = animator;
        this.clock = clock;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** 남은 비행 시간. 최소 1ms — 0 을 주면 SDK 가 이동을 건너뛴다. */
    public long remainingMs() {
        return Math.max(1L, totalMs - elapsedMs);
    }

    public void flyTo(double lat, double lng, float zoom, long durationMs, Arrival cb) {
        cancel();
        arrival = cb;
        targetLat = lat;
        targetLng = lng;
        targetZoom = zoom;
        totalMs = Math.max(1L, durationMs);
        elapsedMs = 0L;
        startedAtMs = clock.getAsLong();
        inFlight = true;
        frozen = false;
        animate(totalMs);
    }

    /** 공중 정지. 얼릴 비행이 없으면 false. */
    public boolean freeze() {
        if (!inFlight || frozen) return false;
        elapsedMs += clock.getAsLong() - startedAtMs;
        generation++; // 이 뒤에 오는 콜백은 전부 우리가 만든 중단이다 — 버린다.
        animator.stop();
        frozen = true;
        return true;
    }

    /** 동결 지점부터 잔여 구간을 이어간다. 얼려 둔 비행이 없으면 false. */
    public boolean resume() {
        if (!inFlight || !frozen) return false;
        frozen = false;
        startedAtMs = clock.getAsLong();
        animate(remainingMs());
        return true;
    }

    /** 비행을 버린다 — 콜백은 도착으로도 중단으로도 보고되지 않는다. */
    public void cancel() {
        generation++;
        if (inFlight) animator.stop();
        inFlight = false;
        frozen = false;
        arrival = null;
        elapsedMs = 0L;
        totalMs = 0L;
    }

    /** 애니메이션 없이 즉시 이동 (진행 중 비행은 버린다). */
    public void moveTo(double lat, double lng, float zoom) {
        cancel();
        animator.moveTo(lat, lng, zoom);
    }

    private void animate(long durationMs) {
        final int gen = ++generation;
        animator.animateTo(targetLat, targetLng, targetZoom, durationMs,
                new CameraAnimator.Listener() {
                    @Override
                    public void onArrive() {
                        if (gen != generation || !inFlight) return;
                        Arrival cb = settle();
                        if (cb != null) cb.onArrive();
                    }

                    @Override
                    public void onCancel() {
                        if (gen != generation || !inFlight) return;
                        Arrival cb = settle();
                        if (cb != null) cb.onInterrupted();
                    }
                });
    }

    @Nullable
    private Arrival settle() {
        Arrival cb = arrival;
        inFlight = false;
        frozen = false;
        arrival = null;
        return cb;
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayCameraTest'`
Expected: PASS (10 tests).

- [ ] **Step 7: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: add a testable camera seam with mid-flight freeze and resume"
```

---

## Task 4: `ReplayEngine` — 재생 상태 머신

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayScheduler.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/MainThreadReplayScheduler.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayEngine.java`
- Create: `android/app/src/test/java/com/traveltrace/app/ui/map/FakeReplayScheduler.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayEngineTest.java`

**Interfaces:**
- Consumes: `ReplayPlan`, `ReplayCamera`, `CameraAnimator`, `MapUiState.Stop`(9-인자), `MapUiState.Speed`
- Produces:
  - `interface ReplayScheduler { void postDelayed(Runnable task, long delayMs); void cancelAll(); }`
  - `class MainThreadReplayScheduler implements ReplayScheduler` — 무인자 생성자
  - `class ReplayEngine` — 생성자 `ReplayEngine(ReplayScheduler scheduler)`, 상수 `NO_MOVE = -1`,
    `interface ReplayEngine.Listener { void onReplayChanged(); }`,
    메서드 `setListener`, `setStops(List<MapUiState.Stop>)`, `stops()`, `activeIndex()`,
    `currentIndex()`, `isPlaying()`, `isCinema()`, `speed()`, `attachCamera(CameraAnimator)`,
    `detachCamera()`, `pause()`, `release()`, `togglePlay()`, `next()`, `prev()`,
    `jumpTo(int)`, `setSpeed(MapUiState.Speed)`, `setCinema(boolean)`
  - 테스트 더블 `FakeReplayScheduler` — `int pendingCount()`, `long lastDelayMs()`, `void runPending()`, `int cancelCount`

- [ ] **Step 1: 테스트용 `FakeReplayScheduler` 를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/ui/map/FakeReplayScheduler.java`:

```java
package com.traveltrace.app.ui.map;

import java.util.ArrayList;
import java.util.List;

/**
 * dwell 타이머를 테스트가 손으로 굴리게 해 주는 더블. 진짜 시간을 흘려보내지 않으므로
 * 재생 순회 테스트가 빠르고 결정적이다.
 */
public class FakeReplayScheduler implements ReplayScheduler {

    private final List<Runnable> pending = new ArrayList<>();
    private long lastDelayMs = -1L;
    public int cancelCount;

    @Override
    public void postDelayed(Runnable task, long delayMs) {
        lastDelayMs = delayMs;
        pending.add(task);
    }

    @Override
    public void cancelAll() {
        cancelCount++;
        pending.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    public long lastDelayMs() {
        return lastDelayMs;
    }

    /** 예약된 작업을 전부 실행한다. 실행 중 새로 예약된 건 다음 호출로 미룬다. */
    public void runPending() {
        List<Runnable> due = new ArrayList<>(pending);
        pending.clear();
        for (Runnable r : due) {
            r.run();
        }
    }
}
```

Create `android/app/src/test/java/com/traveltrace/app/ui/map/ReplayEngineTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * S2 수용 기준의 본체. 지도도 실제 시간도 없이 상태 머신만 검증한다
 * — 카메라는 {@link FakeCameraAnimator}, dwell 은 {@link FakeReplayScheduler} 가 대신한다.
 *
 * <p>Robolectric 러너를 쓰는 이유는 {@code MapUiState.Stop} 이 {@code android.net.Uri} 를
 * 필드로 갖기 때문이다(값은 null 이지만 타입이 로드된다).
 */
@RunWith(RobolectricTestRunner.class)
public class ReplayEngineTest {

    private FakeReplayScheduler scheduler;
    private FakeCameraAnimator animator;
    private ReplayEngine engine;
    private int changeCount;

    /** 파리 랜드마크 4곳 — 실제 좌표라 비행 시간 계산도 현실적인 값이 나온다. */
    private static List<MapUiState.Stop> fourStops() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8,
                48.8738, 2.2950, null));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 0, 0xFFB7C6D6,
                48.8584, 2.2945, null));
        stops.add(new MapUiState.Stop("louvre", "루브르", "15:40", true, 0, 0xFFCDBFA1,
                48.8606, 2.3376, null));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 0, 0xFFD7D0BF,
                48.8867, 2.3431, null));
        return stops;
    }

    @Before
    public void setUp() {
        scheduler = new FakeReplayScheduler();
        animator = new FakeCameraAnimator();
        engine = new ReplayEngine(scheduler);
        engine.setListener(() -> changeCount++);
        engine.setStops(fourStops());
        engine.attachCamera(animator);
    }

    /** dwell 만료 → 비행 시작 → 도착 을 한 번 수행한다. */
    private void advanceOneStop() {
        scheduler.runPending();
        animator.arrive();
    }

    @Test
    public void attachingTheCameraDoesNotMoveIt() {
        // 진입 직후 카메라는 MapRouteRenderer 가 맞춘 전체 경로 bounds 여야 한다.
        assertEquals(0, animator.moveCount);
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void playAdvancesThroughStopsInOrder() {
        engine.togglePlay();
        assertTrue(engine.isPlaying());
        assertEquals(0, engine.activeIndex());

        advanceOneStop();
        assertEquals(1, engine.activeIndex());

        advanceOneStop();
        assertEquals(2, engine.activeIndex());

        advanceOneStop();
        assertEquals(3, engine.activeIndex());
    }

    @Test
    public void playStopsAtTheLastStop() {
        engine.togglePlay();
        advanceOneStop();
        advanceOneStop();
        advanceOneStop();

        assertEquals(3, engine.activeIndex());
        assertFalse("마지막 스톱에 닿으면 스스로 멈춘다", engine.isPlaying());
        assertEquals("멈춘 뒤 새 타이머가 남지 않는다", 0, scheduler.pendingCount());
    }

    /** 스톱이 하나뿐이면 갈 곳이 없다 — dwell 이 만료되는 순간 스스로 멈춘다. */
    @Test
    public void playOnASingleStopRouteStopsAtTheFirstDwell() {
        engine.setStops(fourStops().subList(0, 1));
        engine.togglePlay();
        assertTrue(engine.isPlaying());

        scheduler.runPending();

        assertFalse(engine.isPlaying());
        assertEquals(0, engine.activeIndex());
    }

    @Test
    public void playFromTheEndRestartsFromTheFirstStop() {
        engine.jumpTo(3);
        animator.arrive();
        assertEquals(3, engine.activeIndex());

        engine.togglePlay();

        assertEquals("끝에서 재생하면 처음부터 다시 (프로토타입 togglePlay)", 0, engine.activeIndex());
        assertTrue(engine.isPlaying());
    }

    @Test
    public void dwellUsesTheSelectedSpeedPreset() {
        engine.setSpeed(MapUiState.Speed.FAST);
        engine.togglePlay();

        assertEquals(ReplayPlan.DWELL_FAST_MS, scheduler.lastDelayMs());
    }

    @Test
    public void movingIndexTracksTheFlightDestination() {
        engine.togglePlay();
        scheduler.runPending(); // 비행 시작, 아직 도착 전

        assertEquals("도착 전에는 active 가 안 움직인다", 0, engine.activeIndex());
        assertEquals("목적지는 moving 이 안다", 1, engine.currentIndex());

        animator.arrive();

        assertEquals(1, engine.activeIndex());
        assertEquals("도착했으면 이동 중이 아니다", 1, engine.currentIndex());
    }

    @Test
    public void pauseMidFlightFreezesAndResumeContinuesTheRemainder() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중
        int animatesBeforePause = animator.animateCount;

        engine.togglePlay(); // 일시정지

        assertFalse(engine.isPlaying());
        assertEquals("공중 정지는 애니메이션을 멈춘다", 1, animator.stopCount);
        assertEquals("정지가 새 비행을 만들지 않는다", animatesBeforePause, animator.animateCount);

        engine.togglePlay(); // 재개

        assertTrue(engine.isPlaying());
        assertEquals("잔여 구간 비행 1건이 새로 뜬다", animatesBeforePause + 1, animator.animateCount);
        assertEquals("목적지는 그대로", 1, engine.currentIndex());

        animator.arrive();

        assertEquals(1, engine.activeIndex());
        assertTrue("재개 후에도 순회가 이어진다", scheduler.pendingCount() > 0);
    }

    @Test
    public void pauseWhileRestingJustStopsTheTimer() {
        engine.togglePlay();
        assertTrue(scheduler.pendingCount() > 0);

        engine.togglePlay();

        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void nextFliesToTheFollowingStopAndStopsPlayback() {
        engine.togglePlay();

        engine.next();

        assertFalse("수동 조작은 자동 재생을 끈다", engine.isPlaying());
        assertEquals(1, engine.currentIndex());
        animator.arrive();
        assertEquals(1, engine.activeIndex());
    }

    @Test
    public void nextMidFlightAdvancesFromTheDestinationNotTheOrigin() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중

        engine.next();

        assertEquals("비행 중 '다음'은 목적지(1)의 다음(2)으로 간다", 2, engine.currentIndex());
    }

    @Test
    public void prevStopsAtTheFirstStop() {
        engine.prev();
        animator.arrive();

        assertEquals(0, engine.activeIndex());
        assertEquals("첫 스톱에서 이전은 비행을 만들지 않는다", 0, animator.animateCount);
    }

    @Test
    public void nextStopsAtTheLastStop() {
        engine.jumpTo(3);
        animator.arrive();
        int animatesAtEnd = animator.animateCount;

        engine.next();

        assertEquals(3, engine.activeIndex());
        assertEquals("마지막 스톱에서 다음은 비행을 만들지 않는다", animatesAtEnd, animator.animateCount);
    }

    @Test
    public void jumpToTheCurrentStopRecentersWithoutFlying() {
        engine.jumpTo(0);

        assertEquals("같은 스톱으로의 점프는 즉시 이동", 1, animator.moveCount);
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void jumpToClampsAtBothEnds() {
        engine.jumpTo(-5);
        animator.arrive();
        assertEquals(0, engine.activeIndex());

        engine.jumpTo(99);
        animator.arrive();
        assertEquals(3, engine.activeIndex());
    }

    @Test
    public void cinemaRestsCloserAndRecentersImmediately() {
        engine.setCinema(true);

        assertTrue(engine.isCinema());
        assertEquals(1, animator.moveCount);
        assertEquals(ReplayPlan.REST_ZOOM_CINEMA, animator.lastZoom, 0.001f);
    }

    @Test
    public void cinemaDuringAFlightDoesNotYankTheCamera() {
        engine.togglePlay();
        scheduler.runPending(); // 비행 중

        engine.setCinema(true);

        assertEquals("비행 중에는 즉시 이동으로 끊지 않는다", 0, animator.moveCount);
    }

    @Test
    public void externalCameraInterruptionSettlesAtTheDestinationAndStops() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중

        animator.cancel(); // 사용자가 지도를 직접 만졌다

        assertEquals(1, engine.activeIndex());
        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void detachingTheCameraPausesPlayback() {
        engine.togglePlay();

        engine.detachCamera();

        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void withoutACameraTheMachineStillAdvancesInstantly() {
        // 회전 직후처럼 카메라가 없어도 상태 머신이 멈추면 안 된다.
        engine.detachCamera();

        engine.next();

        assertEquals(1, engine.activeIndex());
        assertEquals(1, engine.currentIndex());
    }

    /**
     * 카메라 없이도 자동 순회가 리스너에 <em>알려져야</em> 한다.
     *
     * <p>이 테스트가 {@code glideTo} 의 camera-null 분기에 있는 {@code notifyChanged()} 의
     * 존재 이유를 못 박는다. next/prev/jumpTo 경로에선 호출부가 어차피 또 알리므로 그 줄이
     * 중복처럼 보이지만, {@code scheduleAdvance} 로 들어온 hop 에는 뒤따르는 호출부 알림이
     * 없다 — 지우면 카메라 없이 재생할 때 인덱스가 조용히 움직인다.
     */
    @Test
    public void headlessAutoPlayStillNotifiesEachHop() {
        engine.detachCamera();
        engine.togglePlay();
        int before = changeCount;

        scheduler.runPending(); // dwell 만료 → 카메라가 없으니 즉시 도착 처리

        assertEquals(1, engine.activeIndex());
        assertTrue("카메라가 없어도 각 hop 이 알려져야 한다", changeCount > before);
    }

    @Test
    public void releaseCancelsEverythingAndSilencesTheListener() {
        engine.togglePlay();
        int before = changeCount;

        engine.release();
        engine.next();

        assertEquals("release 뒤에는 상태 알림이 없다", before, changeCount);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void emptyRouteIgnoresEveryControl() {
        engine.setStops(new ArrayList<>());

        engine.togglePlay();
        engine.next();
        engine.prev();
        engine.jumpTo(2);

        assertFalse(engine.isPlaying());
        assertEquals(0, engine.activeIndex());
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void setStopsResetsPlaybackToTheStart() {
        engine.togglePlay();
        advanceOneStop();
        assertEquals(1, engine.activeIndex());

        engine.setStops(fourStops());

        assertEquals(0, engine.activeIndex());
        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayEngineTest'`
Expected: 컴파일 실패 — `cannot find symbol: class ReplayEngine` / `class ReplayScheduler`.

- [ ] **Step 3: 스케줄러 seam 을 만든다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayScheduler.java`:

```java
package com.traveltrace.app.ui.map;

/**
 * dwell 타이머 seam. 실제 시간을 흘려보내지 않고 재생 순회를 테스트하기 위한 경계다.
 * 재생 타이밍은 UI 프레임과 붙어 있어 {@code AppExecutors.io()} 가 아니라 메인 스레드에서 돈다.
 */
public interface ReplayScheduler {

    void postDelayed(Runnable task, long delayMs);

    /** 이 스케줄러가 예약한 작업을 전부 취소한다. */
    void cancelAll();
}
```

Create `android/app/src/main/java/com/traveltrace/app/ui/map/MainThreadReplayScheduler.java`:

```java
package com.traveltrace.app.ui.map;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * main {@link Looper} 기반 구현. 모든 예약에 같은 토큰을 달아 {@code cancelAll()} 한 번으로
 * 남김없이 지운다 — 화면 이탈 시 타이머가 새는 것을 막는 장치다(S2 수용 기준).
 */
public final class MainThreadReplayScheduler implements ReplayScheduler {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object token = new Object();

    @Override
    public void postDelayed(Runnable task, long delayMs) {
        handler.postAtTime(task, token, SystemClock.uptimeMillis() + delayMs);
    }

    @Override
    public void cancelAll() {
        handler.removeCallbacksAndMessages(token);
    }
}
```

- [ ] **Step 4: `ReplayEngine` 을 만든다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/ReplayEngine.java`:

```java
package com.traveltrace.app.ui.map;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 리플레이 상태 머신. 프로토타입(prototype/TravelTrace.html)의
 * {@code schedule}/{@code glideTo}/{@code togglePlay}/{@code next}/{@code prev}/{@code jumpTo}
 * 를 네이티브로 옮긴 것이다. 카메라 연출은 {@link ReplayCamera} 에, 시간 계산은
 * {@link ReplayPlan} 에 위임하므로 여기 남는 건 "언제 무엇이 활성인가" 뿐이다.
 *
 * <p><b>active 와 moving 을 나누는 이유:</b> 카메라가 날아가는 동안 사진 카드는 아직 출발지를
 * 보여준다 — 카드는 <em>도착할 때</em> 팝으로 바뀐다(S2 수용 기준). 그래서 {@code activeIndex}
 * 는 도착 시점에만 갱신하고, "지금 어디로 가는 중"은 {@code movingIndex} 가 따로 안다.
 * 이전/다음/점프는 출발지가 아니라 <em>목적지</em>를 기준으로 계산해야 자연스러우므로
 * {@link #currentIndex()} 를 쓴다(프로토타입 {@code curIndex()}).
 */
public class ReplayEngine {

    /** 진행 중인 카메라 이동이 없음. */
    public static final int NO_MOVE = -1;

    public interface Listener {
        void onReplayChanged();
    }

    private final ReplayScheduler scheduler;
    private final List<MapUiState.Stop> stops = new ArrayList<>();

    @Nullable private ReplayCamera camera;
    @Nullable private Listener listener;

    private int activeIndex;
    private int movingIndex = NO_MOVE;
    private boolean playing;
    private boolean cinema;
    private MapUiState.Speed speed = MapUiState.Speed.NORMAL;

    public ReplayEngine(ReplayScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 엔진이 들고 있는 스톱 목록 그대로. 호출부(ViewModel)는 이걸 {@code MapUiState} 에 실어
     * 보내는데, {@code MapReplayFragment.sameRoute()} 가 <em>원소 참조 동일성</em>으로 "경로가
     * 실제로 바뀌었는가"를 판별하므로 여기서 새 {@code Stop} 을 만들면 안 된다.
     */
    public List<MapUiState.Stop> stops() {
        return stops;
    }

    public int activeIndex() {
        return activeIndex;
    }

    /** 이동 중이면 목적지, 아니면 현재 (프로토타입 curIndex). */
    public int currentIndex() {
        return movingIndex != NO_MOVE ? movingIndex : activeIndex;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isCinema() {
        return cinema;
    }

    public MapUiState.Speed speed() {
        return speed;
    }

    /** 새 여행을 실었다 — 재생을 처음으로 되돌린다. */
    public void setStops(List<MapUiState.Stop> next) {
        scheduler.cancelAll();
        if (camera != null) camera.cancel();
        stops.clear();
        stops.addAll(next);
        activeIndex = 0;
        movingIndex = NO_MOVE;
        playing = false;
        notifyChanged();
    }

    /**
     * 지도가 준비됐다. <b>여기서 카메라를 움직이지 않는다</b> — 진입 직후 카메라는
     * {@code MapRouteRenderer.cameraFor()} 가 맞춘 전체 경로 bounds 여야 하고, 그걸 스톱 0 으로
     * 스냅해 버리면 사용자가 여행 전체를 볼 기회를 잃는다. 첫 이동은 재생/점프에서 일어난다.
     */
    public void attachCamera(CameraAnimator animator) {
        camera = new ReplayCamera(animator);
    }

    /**
     * 뷰가 죽는다. 재생을 멈추고 카메라를 놓는다.
     *
     * <p>동결해 둔 잔여 비행은 카메라와 함께 사라진다 — 회전 뒤 재생을 누르면 잔여 구간을
     * 잇는 대신 현재 스톱에서 dwell 부터 다시 시작한다. 알려진 절충이다(뷰가 사라진 동안
     * 카메라 위치를 신뢰할 수 없다).
     */
    public void detachCamera() {
        pause();
        camera = null;
    }

    /** 재생 중이면 멈춘다(비행 중이면 공중 정지). 이미 멈춰 있으면 아무 일도 없다. */
    public void pause() {
        if (!playing) return;
        scheduler.cancelAll();
        if (camera != null) camera.freeze();
        playing = false;
        notifyChanged();
    }

    /** ViewModel 이 죽을 때. 타이머·비행·리스너를 전부 놓는다. */
    public void release() {
        scheduler.cancelAll();
        if (camera != null) camera.cancel();
        camera = null;
        listener = null;
        playing = false;
    }

    public void togglePlay() {
        if (stops.isEmpty()) return;
        if (playing) {
            pause();
            return;
        }
        if (camera != null && camera.isFrozen()) {
            // 공중 정지해 둔 비행이 있다 — 잔여 구간을 이어간다. 도착 콜백은 얼리기 전에
            // 걸어 둔 것이 그대로 살아 있으므로 순회도 알아서 이어진다.
            playing = true;
            camera.resume();
            notifyChanged();
            return;
        }
        if (activeIndex >= stops.size() - 1) {
            // 끝에서 재생하면 처음부터 (프로토타입 togglePlay).
            activeIndex = 0;
            movingIndex = NO_MOVE;
            restAtActive();
        }
        playing = true;
        notifyChanged();
        scheduleAdvance();
    }

    public void next() {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        if (activeIndex < stops.size() - 1) {
            glideTo(activeIndex + 1, null);
        }
        notifyChanged();
    }

    public void prev() {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        if (activeIndex > 0) {
            glideTo(activeIndex - 1, null);
        }
        notifyChanged();
    }

    public void jumpTo(int index) {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        int target = clamp(index);
        if (target != activeIndex) {
            glideTo(target, null);
        } else {
            restAtActive();
        }
        notifyChanged();
    }

    /** 다음 hop 부터 적용된다 — 이미 예약된 dwell 은 다시 걸지 않는다(프로토타입과 동일). */
    public void setSpeed(MapUiState.Speed next) {
        speed = next;
        notifyChanged();
    }

    public void setCinema(boolean next) {
        cinema = next;
        // 비행 중이면 즉시 이동으로 끊지 않는다 — 다음 착륙부터 새 줌이 적용된다.
        if (movingIndex == NO_MOVE) restAtActive();
        notifyChanged();
    }

    // ---- 내부 ----

    /**
     * dwell 후 다음 스톱으로 (프로토타입 schedule).
     *
     * <p>프로토타입은 마지막 스톱에 도착한 뒤 다음 dwell 을 걸지 않을 뿐 {@code playing} 을
     * true 로 남겨 둔다 — 재생 버튼이 계속 일시정지 아이콘인 채 아무 일도 안 일어나는 상태다.
     * 여기선 <b>도착 시점에 스스로 멈춘다.</b> 그래야 "끝에서 재생하면 처음부터"(togglePlay)
     * 규칙이 실제로 닿을 수 있는 상태가 된다.
     */
    private void scheduleAdvance() {
        scheduler.postDelayed(() -> {
            if (activeIndex >= stops.size() - 1) {
                // 스톱이 하나뿐인 여행 — 갈 곳이 없다.
                playing = false;
                notifyChanged();
                return;
            }
            glideTo(activeIndex + 1, () -> {
                if (!playing) return;
                if (activeIndex < stops.size() - 1) {
                    scheduleAdvance();
                } else {
                    playing = false;
                    notifyChanged();
                }
            });
        }, ReplayPlan.dwellMs(speed));
    }

    /** 수동 조작의 공통 앞부분: 타이머·비행을 끊고 "가고 있던 곳"에 앉힌다. */
    private void settleAtCurrent() {
        scheduler.cancelAll();
        int current = currentIndex();
        if (camera != null) camera.cancel();
        activeIndex = clamp(current);
        movingIndex = NO_MOVE;
        playing = false;
    }

    private int clamp(int index) {
        if (stops.isEmpty()) return 0;
        return Math.min(Math.max(index, 0), stops.size() - 1);
    }

    private void glideTo(int index, @Nullable Runnable done) {
        final int target = clamp(index);
        if (camera == null) {
            // 지도가 아직 안 붙었거나(헤드리스 테스트) 떨어진 상태 — 연출 없이 즉시 도착으로
            // 처리한다. 그러지 않으면 상태 머신이 영영 moving 에 갇힌다.
            //
            // 여기서 notifyChanged() 를 부르면 next/prev/jumpTo 경로에선 호출부의 마지막
            // notifyChanged() 와 겹쳐 같은 값이 두 번 발행된다. 그래도 <b>부른다</b>:
            // scheduleAdvance() 로 들어온 자동 순회에는 뒤따르는 호출부 알림이 없어서,
            // 빼면 카메라 없이 재생할 때 인덱스 변화가 조용히 묻힌다. 중복 발행은 무해하다 —
            // Fragment 의 도착 팝은 activeIndex 가 실제로 달라졌을 때만 튀고(lastPoppedIndex),
            // sameRoute 가드는 Stop 인스턴스가 그대로라 다시 그리지 않는다.
            activeIndex = target;
            movingIndex = NO_MOVE;
            notifyChanged();
            if (done != null) done.run();
            return;
        }
        MapUiState.Stop from = stops.get(clamp(activeIndex));
        MapUiState.Stop to = stops.get(target);
        movingIndex = target;
        long duration = ReplayPlan.flightMs(speed,
                ReplayPlan.distanceMeters(from.lat, from.lng, to.lat, to.lng));
        camera.flyTo(to.lat, to.lng, ReplayPlan.restZoom(cinema), duration,
                new ReplayCamera.Arrival() {
                    @Override
                    public void onArrive() {
                        activeIndex = target;
                        movingIndex = NO_MOVE;
                        notifyChanged();
                        if (done != null) done.run();
                    }

                    @Override
                    public void onInterrupted() {
                        // 사용자가 지도를 직접 만져 비행이 끊겼다 — 목적지에 앉히고 재생을 멈춘다.
                        // 여기서 계속 재생하면 사용자가 방금 옮긴 화면을 곧바로 다시 뺏는다.
                        scheduler.cancelAll();
                        activeIndex = target;
                        movingIndex = NO_MOVE;
                        playing = false;
                        notifyChanged();
                    }
                });
    }

    private void restAtActive() {
        if (camera == null || stops.isEmpty()) return;
        MapUiState.Stop stop = stops.get(clamp(activeIndex));
        camera.moveTo(stop.lat, stop.lng, ReplayPlan.restZoom(cinema));
    }

    private void notifyChanged() {
        if (listener != null) listener.onReplayChanged();
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ReplayEngineTest'`
Expected: PASS (24 tests).

- [ ] **Step 6: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: add the replay state machine with dwell, freeze and manual controls"
```

---

## Task 5: `MapReplayViewModel` 을 엔진 위임으로 바꾼다

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java` (전면 재작성)
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelTest.java` (추가)

**Interfaces:**
- Consumes: `ReplayEngine`, `MainThreadReplayScheduler`, `ReplayScheduler`, `CameraAnimator`, `TripRepository.open(String, Callback<TripDetail>)`
- Produces: `MapReplayViewModel` 의 새 공개 메서드
  - `void attachCamera(CameraAnimator animator)`
  - `void detachCamera()`
  - `void pausePlayback()`
  - 기존 공개 메서드 시그니처(`load`, `state`, `tripId`, `tripIdOf`, `unknownThumbTones`, `setSatellite`, `togglePlay`, `setSpeed`, `jumpTo`, `next`, `prev`, `setCinema`)와 2-인자 생성자는 **그대로 유지**한다 — 기존 테스트 3개가 이 표면에 의존한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelTest.java` 의 클래스 맨 끝(마지막 `}` 바로 위)에 추가한다. 상단 import 에 `com.traveltrace.app.ui.map.FakeCameraAnimator` 는 같은 패키지라 필요 없다.

```java
    /** 지도가 붙으면 재생이 실제 카메라를 움직인다 — VM 이 엔진에 정말 위임했는지의 증거. */
    @Test
    public void attachedCameraMovesWhenTheUserJumps() {
        MapReplayViewModel vm = newFixtureVm();
        FakeCameraAnimator animator = new FakeCameraAnimator();
        vm.attachCamera(animator);

        vm.jumpTo(2);

        assertEquals(1, animator.animateCount);
        assertEquals(48.8600d, animator.lastLat, 0.0001d); // 픽스처 index 2 = 센강
    }

    /** 도착해야 카드가 바뀐다 — 비행 중에는 activeIndex 가 그대로다. */
    @Test
    public void activeIndexOnlyMovesOnArrival() {
        MapReplayViewModel vm = newFixtureVm();
        FakeCameraAnimator animator = new FakeCameraAnimator();
        vm.attachCamera(animator);

        vm.jumpTo(2);
        assertEquals(0, vm.state().getValue().activeIndex);

        animator.arrive();
        assertEquals(2, vm.state().getValue().activeIndex);
    }

    /** 화면이 사라지면 재생이 멈춘다 (타이머·애니메이션 누수 방지 — S2 수용 기준). */
    @Test
    public void detachCameraStopsPlayback() {
        MapReplayViewModel vm = newFixtureVm();
        vm.attachCamera(new FakeCameraAnimator());
        vm.togglePlay();
        assertTrue(vm.state().getValue().playing);

        vm.detachCamera();

        assertFalse(vm.state().getValue().playing);
    }

    @Test
    public void pausePlaybackIsIdempotent() {
        MapReplayViewModel vm = newFixtureVm();
        vm.attachCamera(new FakeCameraAnimator());

        vm.pausePlayback();
        assertFalse(vm.state().getValue().playing);

        vm.togglePlay();
        vm.pausePlayback();
        vm.pausePlayback();

        assertFalse(vm.state().getValue().playing);
    }

    /** sameRoute 가드의 전제: UI-only 갱신은 같은 Stop 인스턴스를 그대로 다시 보낸다. */
    @Test
    public void uiOnlyUpdatesKeepTheSameStopInstances() {
        MapReplayViewModel vm = newFixtureVm();
        MapUiState before = vm.state().getValue();

        vm.setSatellite(true);
        MapUiState after = vm.state().getValue();

        assertTrue(before != after);
        for (int i = 0; i < before.stops.size(); i++) {
            assertTrue("Stop 인스턴스가 그대로여야 Fragment 가 다시 그리지 않는다",
                    before.stops.get(i) == after.stops.get(i));
        }
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapReplayViewModelTest'`
Expected: 컴파일 실패 — `cannot find symbol: method attachCamera(FakeCameraAnimator)`.

- [ ] **Step 3: `MapReplayViewModel` 을 재작성한다**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java` 를 아래 내용으로 통째로 교체한다.

```java
package com.traveltrace.app.ui.map;

import android.content.ContentUris;
import android.provider.MediaStore;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.StopRow;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 저장된 여행을 TripRepository 에서 불러오고, 재생 상태 머신({@link ReplayEngine})을 감싸
 * 지도 화면 상태({@link MapUiState})로 공급한다.
 *
 * <p><b>재생 로직이 왜 여기 있는가:</b> 카메라는 Fragment 의 {@code GoogleMap} 에 붙어 있지만,
 * 재생 상태(어느 스톱이 활성인지·재생 중인지)는 회전보다 오래 살아야 하고 화면이 렌더하는
 * 단일 SoT 여야 한다. 그래서 엔진은 ViewModel 이 들고, Fragment 는 지도가 준비되면
 * {@link #attachCamera} 로 애니메이터만 꽂아 준다. Fragment 가 상태를 보고 재생 동작을
 * 유도하면 렌더 → 동작 → 렌더 루프가 생기므로, Fragment 는 <em>렌더만</em> 한다.
 */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();
    private final SavedStateHandle savedState;
    private final TripRepository tripRepository;
    private final ReplayEngine engine;

    /** Glide 썸네일이 실패하거나 아직 안 붙었을 때 하단시트 배너에 남는 placeholder 톤. */
    private static final int[] TONES = {
            0xFFD9C9A8, 0xFFB7C6D6, 0xFFA9C6DA, 0xFFCDBFA1, 0xFFC3B69B, 0xFFD7D0BF};

    /** 엔진이 모르는, 순수 화면 정보. */
    private String tripTitle = "";
    private int unknownCount;
    private boolean satellite;
    private boolean loadStarted;

    @Inject
    public MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository) {
        this(savedState, tripRepository, new MainThreadReplayScheduler());
    }

    /** 테스트가 가짜 스케줄러를 넣을 수 있게 분리한 생성자. */
    MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository,
                       ReplayScheduler scheduler) {
        this.savedState = savedState;
        this.tripRepository = tripRepository;
        this.engine = new ReplayEngine(scheduler);
        this.engine.setListener(this::publish);
    }

    /**
     * tripId 가 있으면 저장 여행을, 없으면 디자인 프리뷰 픽스처를 싣는다.
     *
     * <p>Fragment.onViewCreated 는 회전 등 뷰 재생성마다 무조건 다시 부른다. 이 ViewModel 은
     * 뷰보다 오래 살아남으므로 한 번 시작한 적재는 다시 하지 않는다 — 그러지 않으면
     * activeIndex/playing/satellite/cinema/speed 가 전부 기본값으로 리셋되고, 새로 찍어낸
     * {@code Stop} 인스턴스 때문에 {@code MapReplayFragment.sameRoute()} 가드가 깨져
     * 카메라가 whole-route bounds 로 스냅되는 부작용까지 겹친다. {@code state} 값이 아니라
     * 별도 플래그로 판별하는 이유는 저장 여행 적재가 비동기라, 콜백이 오기 전에 두 번째
     * load() 가 들어오면 조회가 중복되기 때문이다.
     */
    public void load() {
        if (loadStarted) return;
        loadStarted = true;
        String tripId = tripId();
        if (tripId == null) {
            MapUiState fixture = ScreenFixtures.map();
            adopt(fixture.tripTitle, fixture.unknownCount, fixture.stops);
            return;
        }
        tripRepository.open(tripId, detail -> {
            if (detail == null) {
                adopt("", 0, new ArrayList<>());
                return;
            }
            adopt(detail.name, detail.unknownCount, toStops(detail));
        });
    }

    private void adopt(String title, int unknown, List<MapUiState.Stop> stops) {
        tripTitle = title;
        unknownCount = unknown;
        engine.setStops(stops); // 엔진이 리스너로 publish() 를 부른다.
    }

    private static List<MapUiState.Stop> toStops(TripDetail detail) {
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
                    row.lng,
                    ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId)));
        }
        return stops;
    }

    /**
     * 엔진 상태 + 화면 정보 → 새 {@link MapUiState}. {@code engine.stops()} 를 그대로 넘기므로
     * Stop 인스턴스는 여행을 새로 열 때만 바뀐다 — 그게 Fragment 의 sameRoute 가드가 기대하는
     * 계약이다.
     */
    private void publish() {
        state.setValue(new MapUiState(tripTitle, unknownCount, engine.stops(),
                engine.activeIndex(), engine.isPlaying(), satellite, engine.isCinema(),
                engine.speed()));
    }

    /** nav argument 로 들어온 저장 여행 식별자. 없으면 null(프리뷰 진입). */
    public static String tripIdOf(SavedStateHandle handle) {
        return handle.get(MapReplayFragment.ARG_TRIP_ID);
    }

    public String tripId() {
        return tripIdOf(savedState);
    }

    public LiveData<MapUiState> state() {
        return state;
    }

    /** 위치 미상 드로어의 썸네일 톤. 실데이터 교체는 S6 소관이다. */
    public int[] unknownThumbTones() {
        return ScreenFixtures.unknownThumbTones();
    }

    // ---- 지도 연결 ----

    /** 지도가 준비됐다. 이 시점부터 재생이 실제 카메라를 움직인다. */
    public void attachCamera(CameraAnimator animator) {
        engine.attachCamera(animator);
    }

    /** 뷰가 죽는다. 재생을 멈추고 카메라를 놓는다. */
    public void detachCamera() {
        engine.detachCamera();
    }

    /** 화면이 백그라운드로 갔다. 이미 멈춰 있으면 아무 일도 없다. */
    public void pausePlayback() {
        engine.pause();
    }

    // ---- 사용자 조작 ----

    public void setSatellite(boolean satellite) {
        this.satellite = satellite;
        publish();
    }

    public void togglePlay() {
        engine.togglePlay();
    }

    public void setSpeed(MapUiState.Speed speed) {
        engine.setSpeed(speed);
    }

    public void jumpTo(int index) {
        engine.jumpTo(index);
    }

    public void next() {
        engine.next();
    }

    public void prev() {
        engine.prev();
    }

    public void setCinema(boolean cinema) {
        engine.setCinema(cinema);
    }

    @Override
    protected void onCleared() {
        engine.release();
        super.onCleared();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapReplayViewModel*'`
Expected: PASS — 새 테스트 5개 + 기존 `MapReplayViewModelTest`·`MapReplayViewModelLoadTest`·`MapReplayViewModelArgsTest` 전부.

> 기존 테스트가 카메라 없이 `togglePlay()`/`jumpTo()` 를 부르면 엔진의 "카메라 없음 = 즉시 도착" 경로를 타므로 종전과 같은 결과가 나온다. 만약 `togglePlay_flipsPlaying` 이 깨진다면 그건 엔진의 "끝에서 재생하면 처음부터" 규칙과 부딪힌 것이다 — 픽스처는 index 0 에서 시작하고 스톱이 6개라 해당되지 않아야 한다. 깨지면 멈추고 보고한다.

- [ ] **Step 5: 전체 회귀를 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 0건, 골든 14장 통과.

- [ ] **Step 6: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java android/app/src/test/java/com/traveltrace/app/ui/map/MapReplayViewModelTest.java
git commit -m "feat: drive map replay state from the replay engine"
```

---

## Task 6: Fragment 배선 — 카메라 attach/detach · 생명주기 정리

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`

**Interfaces:**
- Consumes: `MapReplayViewModel.attachCamera(CameraAnimator)`, `detachCamera()`, `pausePlayback()`, `GoogleMapCameraAnimator(GoogleMap)`
- Produces: 없음 (화면 배선)

**테스트에 관하여:** 이 태스크는 `GoogleMap` 인스턴스가 있어야만 의미가 있는 배선이라 Robolectric 단위 테스트로 검증할 수 없다(Maps SDK 는 shadow 가 없다 — S1 이 `MapRouteRenderer` 를 순수 함수로 뽑아낸 것도 같은 이유다). 재생 로직 자체는 Task 4·5 에서 이미 전부 테스트했으므로, 여기서는 **전체 회귀 + 실기기 수동 확인**으로 마무리한다. Step 4 의 수동 체크리스트를 건너뛰지 않는다.

- [ ] **Step 1: 지도가 준비되면 애니메이터를 꽂는다**

`MapReplayFragment.java` 의 `onMapReady` 를 아래로 교체한다.

```java
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        if (binding == null) return;
        map = googleMap;
        map.getUiSettings().setMapToolbarEnabled(false);

        // 초기 카메라는 여행 스톱에서 결정된다 — 스톱이 없을 때만 파리 고정.
        MapUiState current = vm.state().getValue();
        if (current == null || current.stops.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));
        }

        // 이 시점부터 재생이 실제 카메라를 움직인다. attachCamera 는 카메라를 건드리지
        // 않으므로 바로 아래 render() 의 whole-route bounds fit 이 살아남는다.
        vm.attachCamera(new GoogleMapCameraAnimator(map));

        MapUiState state = vm.state().getValue();
        if (state != null) render(state);
    }
```

- [ ] **Step 2: 생명주기에서 정리한다**

`MapReplayFragment.java` 의 `onDestroyView` 앞에 `onPause` 를 추가하고, `onDestroyView` 를 교체한다.

```java
    @Override
    public void onPause() {
        // 화면이 가려지면 재생을 멈춘다 — 안 그러면 백그라운드에서 dwell 타이머가 계속 돌며
        // 보이지도 않는 카메라를 움직인다. 공중 정지라서 돌아오면 재생으로 이어갈 수 있다.
        vm.pausePlayback();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        // 뷰와 함께 GoogleMap 도 사라진다 — 엔진이 죽은 지도를 붙들지 않도록 먼저 뽑는다.
        vm.detachCamera();
        super.onDestroyView();
        map = null;
        binding = null;
        // 뷰(따라서 GoogleMap)가 새로 만들어지면 그 위엔 아직 아무것도 그려져 있지 않다 —
        // 캐시된 last-drawn 경로를 버려서 다음 onMapReady 가 (VM 의 Stop 인스턴스가 그대로여도)
        // 반드시 다시 그리게 한다.
        lastDrawnStops = null;
    }
```

- [ ] **Step 3: 전체 회귀를 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 0건, 골든 14장 통과.

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 실기기/에뮬레이터 수동 확인**

`local.properties` 에 `MAPS_API_KEY` 가 채워져 있어야 한다(비어 있으면 회색 격자만 보인다). 앱을 설치하고 S1 에서 만든 저장 여행을 연 뒤 아래를 하나씩 확인한다.

- [ ] 진입 직후 카메라가 **전체 경로**를 담고 있다(스톱 하나로 스냅되지 않는다).
- [ ] 재생 ▶ 을 누르면 잠시 뒤 카메라가 다음 스톱으로 부드럽게 이동하고, 도착하면 하단시트 사진 카드가 그 스톱으로 바뀐다.
- [ ] 이동 **도중에** 일시정지를 누르면 카메라가 그 자리에 선다. 다시 재생하면 **남은 구간부터** 이어간다(처음부터 다시 날지 않는다).
- [ ] 마지막 스톱에 닿으면 스스로 멈추고, 그 상태에서 재생을 누르면 첫 스톱부터 다시 시작한다.
- [ ] 이전/다음 버튼이 카메라를 옮기고, 양 끝에서는 아이콘이 흐려진 채 아무 일도 일어나지 않는다.
- [ ] 타임라인 도트를 탭하면 그 스톱으로 날아간다.
- [ ] 속도 프리셋을 바꾸면 다음 hop 의 머무는 시간이 눈에 띄게 달라진다(느긋이 ↔ 빠르게).
- [ ] 상영 모드에 들어가면 카메라가 조금 더 당겨지고, 탭이나 Back 으로 나올 수 있다.
- [ ] 지도/위성 토글이 재생 중에도 카메라를 방해하지 않는다.
- [ ] 재생 중 홈 버튼으로 나갔다 돌아와도 앱이 멀쩡하고, 뒤로 나갔다 다시 들어와도 카메라가 폭주하지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java
git commit -m "feat: wire the map camera into replay with lifecycle cleanup"
```

---

## Task 7: 도착 팝 애니메이션

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/PhotoCardPop.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/PhotoCardPopTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `static void PhotoCardPop.play(View card)`, 상수 `PhotoCardPop.DURATION_MS`, `PhotoCardPop.START_SCALE`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/ui/map/PhotoCardPopTest.java`:

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 팝의 <em>시작 상태</em>만 검증한다. 애니메이션의 끝 상태는 Robolectric 에서 프레임 구동
 * 방식에 따라 흔들려 간헐 실패가 되기 쉬우므로, 결정적으로 관찰 가능한 것 — "play() 직후
 * 카드가 작고 투명하다", "다시 부르면 다시 작아진다" — 만 어서션한다. 실제 스프링 느낌은
 * Task 6 의 실기기 체크리스트에서 눈으로 본다.
 */
@RunWith(RobolectricTestRunner.class)
public class PhotoCardPopTest {

    private View card;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        card = new View(ctx);
    }

    @Test
    public void playArmsTheCardSmallAndTransparent() {
        PhotoCardPop.play(card);

        assertEquals(0f, card.getAlpha(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleX(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleY(), 0.001f);
    }

    @Test
    public void playingAgainRearmsFromTheStart() {
        PhotoCardPop.play(card);
        card.setAlpha(1f);
        card.setScaleX(1f);
        card.setScaleY(1f);

        PhotoCardPop.play(card);

        assertEquals(0f, card.getAlpha(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleX(), 0.001f);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*PhotoCardPopTest'`
Expected: 컴파일 실패 — `cannot find symbol: class PhotoCardPop`.

- [ ] **Step 3: `PhotoCardPop` 을 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/ui/map/PhotoCardPop.java`:

```java
package com.traveltrace.app.ui.map;

import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 스톱에 도착했을 때 사진 카드가 "톡" 하고 나타나는 연출 (프로토타입 {@code playPop}:
 * scale/opacity 스프링).
 *
 * <p>프로토타입은 CSS transition + 이중 rAF 로 초기 상태를 확정했지만, 여기선 초기 값을
 * <em>동기적으로</em> 찍고 바로 애니메이션을 건다 — 뷰 프로퍼티는 즉시 반영되므로 rAF
 * 트릭이 필요 없고, 그 덕에 시작 상태를 단위 테스트로 확인할 수 있다.
 */
public final class PhotoCardPop {

    public static final long DURATION_MS = 460L;
    public static final float START_SCALE = 0.9f;
    private static final float OVERSHOOT_TENSION = 1.4f;

    private PhotoCardPop() {}

    public static void play(View card) {
        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(START_SCALE);
        card.setScaleY(START_SCALE);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(DURATION_MS)
                .setInterpolator(new OvershootInterpolator(OVERSHOOT_TENSION))
                .start();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*PhotoCardPopTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Fragment 가 도착마다 팝을 튼다**

`MapReplayFragment.java` — `lastDrawnStops` 필드 선언 아래에 필드를 추가한다.

```java
    /**
     * 마지막으로 팝을 튼 스톱. activeIndex 는 <em>도착할 때만</em> 바뀌므로, 이 값과 다르면
     * 방금 새 스톱에 닿은 것이다 — 재생/속도/위성 전환 같은 UI-only emission 에는 팝이 안 튄다.
     */
    private int lastPoppedIndex = -1;
```

`render(MapUiState state)` 의 `MapRenderer.renderCinema(binding.cinemaOverlay, state);` 바로 **아래**에 삽입한다.

```java
        if (!state.stops.isEmpty() && state.activeIndex != lastPoppedIndex) {
            lastPoppedIndex = state.activeIndex;
            // 상영 모드에선 큰 카드가, 아니면 하단시트 배너가 팝의 주인공이다.
            PhotoCardPop.play(state.cinema
                    ? binding.cinemaOverlay.cinemaCard
                    : binding.mapBottomSheet.photoBanner);
        }
```

`onDestroyView()` 의 `lastDrawnStops = null;` 바로 아래에 한 줄을 더한다.

```java
        lastDrawnStops = null;
        // 뷰가 새로 생기면 첫 렌더에서 다시 한 번 팝이 나야 한다.
        lastPoppedIndex = -1;
```

- [ ] **Step 6: 전체 회귀를 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 0건, 골든 14장 통과.

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 실기기 수동 확인**

- [ ] 재생 중 각 스톱에 도착할 때마다 하단시트 사진 카드가 살짝 커지며 나타난다.
- [ ] 속도 프리셋을 바꾸거나 지도/위성을 토글해도 팝이 다시 튀지 않는다.
- [ ] 상영 모드에서 재생하면 큰 카드가 팝으로 바뀐다.

- [ ] **Step 8: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map android/app/src/test/java/com/traveltrace/app/ui/map/PhotoCardPopTest.java
git commit -m "feat: pop the photo card when the camera lands on a stop"
```

---

## S2 수용 기준 대조

| ISSUES.md S2 기준 | 어디서 충족되나 |
|---|---|
| 자동 재생이 시각순 스톱을 순회하며 사진 카드를 팝으로 표시 | Task 4 `playAdvancesThroughStopsInOrder` · Task 7 팝 + Task 6 Step 4 수동 확인 |
| 일시정지→재개가 이동 중에도 자연스럽게 잔여 구간을 이어감 | Task 3 `resumeFliesOnlyTheRemainingSegment` · Task 4 `pauseMidFlightFreezesAndResumeContinuesTheRemainder` |
| 타임라인 점프 | Task 4 `jumpToClampsAtBothEnds`, `jumpToTheCurrentStopRecentersWithoutFlying` (스크러버 → `vm.jumpTo` 배선은 S1 에서 이미 되어 있다) |
| 이전/다음 | Task 4 `nextFliesToTheFollowingStopAndStopsPlayback`, `nextMidFlightAdvancesFromTheDestinationNotTheOrigin`, `prevStopsAtTheFirstStop`, `nextStopsAtTheLastStop` |
| 속도 프리셋 | Task 2 `dwellFollowsTheSpeedPreset` · Task 4 `dwellUsesTheSelectedSpeedPreset` |
| 상영 모드 | Task 4 `cinemaRestsCloserAndRecentersImmediately`, `cinemaDuringAFlightDoesNotYankTheCamera` (진입/이탈 UI 는 S1 에서 이미 동작) |
| 지도/위성 토글 | S1 에서 이미 동작 — Task 5 `uiOnlyUpdatesKeepTheSameStopInstances` 가 재생 중에도 경로를 다시 안 그림을 지킨다 |
| 화면 이탈 시 타이머·애니메이션 누수 없이 정리 | Task 4 `detachingTheCameraPausesPlayback`, `releaseCancelsEverythingAndSilencesTheListener` · Task 6 `onPause`/`onDestroyView` |

**의도적으로 범위 밖:** 핀 "빼기"(detach) 및 위치 미상 drawer 실데이터 → **S6**. 3D 비행 → **S7**. `detachButton` 은 지금처럼 토스트만 띄운 채로 남는다.
