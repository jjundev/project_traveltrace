# TravelTrace 화면 구현 계획 (Screens-First)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로토타입 `prototype/TravelTrace.html`에 디자인된 모든 화면을 로직 없이 정적으로 구현한다 — 각 화면은 픽스처 UiState만으로 프로토타입과 육안 동등하게 렌더된다.

**Architecture:** 화면마다 `XxxUiState`(불변 데이터) · `XxxRenderer`(binding+state → 뷰 반영, 순수) · `XxxFragment`(라이프사이클만) · `XxxViewModel`(LiveData 공급)로 4분할한다. ViewModel은 이 단계에서 `ScreenFixtures`(프로토타입 값 그대로)를 공급하고, 이후 로직 에픽은 **ViewModel의 공급원만 Repository로 교체**한다 — Renderer/레이아웃은 손대지 않는다. Renderer가 순수하므로 Robolectric이 Fragment·Hilt 없이 레이아웃을 인플레이트해 렌더 결과를 검증한다.

**Tech Stack:** Java 17, Android View/XML(Compose 금지), ViewBinding, Fragment + Navigation Component, Hilt(annotationProcessor), Material 3, Google Maps SDK, Robolectric(신규), JUnit 4.

## Global Constraints

- **Java + View/XML 전용.** Kotlin·Compose 도입 금지 (`plan/00-overview.md` 공통 DoD).
- **이 계획의 범위는 화면(정적 UI)뿐이다.** 사진 로딩·EXIF·AI·지오코딩·리플레이 애니메이션·Room 저장 로직은 **구현하지 않는다.** 데이터는 전부 `ScreenFixtures`에서 온다.
- **디자인 토큰은 기존 리소스만 사용한다.** 색은 `colors.xml` 시맨틱 별칭만 참조(`color_palette.xml` 직접 참조 금지), 치수는 `dimens.xml`, 타이포/컴포넌트는 `styles.xml`의 `TextAppearance.TravelTrace.*` / `Widget.TravelTrace.*`. 하드코딩 hex·px 금지.
- **사용자 대면 문자열은 전부 `strings.xml`(한국어)로 분리.**
- **기준 해상도**: 390x844 (1 CSS px ≈ 1 dp).
- **프로토타입의 목업 상태바(9:41 · 5G · 배터리)는 이식하지 않는다.** 네이티브는 실제 시스템 상태바를 쓴다. 프로토타입의 `statusColor`(위성 뷰에서 흰색)는 `windowLightStatusBar` 토글로 대응한다.
- **프로토타입의 CSS 카메라 합성(`applyLayer`/`startFlight`/rAF swoop)은 이식 금지.** 연출은 에픽 12~14 소관 (`plan/00-overview.md`).
- **알려진 충실도 격차**는 코드 주석으로 명시한다: VectorDrawable은 `stroke-dasharray`를 지원하지 않고, 네이티브 elevation 그림자는 CSS 소프트 글로우와 동일하지 않으며, 실제 backdrop blur는 post-v1이다(불투명 스크림 폴백 — 기존 `pill_location_scrim` 선례).
- **모든 신규 파일은 `com.traveltrace.app` 네임스페이스 아래.** minSdk 33 / targetSdk 35 / compileSdk 35.

---

## 프로토타입에서 추출한 화면 인벤토리

프로토타입은 `screen` state 4종 + 공용 지도 레이어 + 오버레이 3종 + 토스트로 구성된다. (근거: 언팩한 `template.html`의 `<x-dc>` 마크업 및 `class Component extends DCLogic`의 `state.screen` / `state.sheet` / `state.cinema`.)

| # | 화면 / 오버레이 | 프로토타입 조건 | 구성 요소 | 담당 태스크 |
|---|---|---|---|---|
| 1 | **HOME — 여행 목록** | `screen:'home'` + `hasTrips` | 헤더("여행" / "다녀온 길을 다시 떠나요" / 설정 아이콘), 여행 카드 2종(파리=활성, 제주=잠김), 플로팅 CTA "새 여행 만들기" | 3 |
| 2 | **HOME — 빈 상태** | `screen:'home'` + `emptyHome` | 120dp 일러스트, "첫 여행을 만들어 보세요", 안내 2줄, 동일 CTA | 3 |
| 3 | **토스트** | `toast` 비어있지 않음 | 하단 110dp 다크 pill (`openTripLocked`·`detach`가 사용) | 3 |
| 4 | **SELECT — 사진 선택** | `screen:'select'` | 뒤로가기+제목, 여행 기간 카드("2024. 6. 12 – 6. 15 · 사진 94장" + "변경"), 카운터 행, 3열 그리드(18타일·선택 링·체크 배지·라벨 pill), CTA "분석 시작" | 4 |
| 5 | **ANALYZE — 진행** | `screen:'analyze'` + `analyzing` | 플로팅 뒤로가기, 진행 카드(스피너 · "사진 분석 중" · "N / 82" · 진행바 · 인식 장소) | 5 |
| 6 | **ANALYZE — 완료** | `screen:'analyze'` + `done` | 진행 카드가 완료형으로 전환(녹색 체크 · "분석 완료" · "경로 6 · 위치 미상 5"), 하단 CTA "지도에서 여행 보기" | 5 |
| 7 | **ANALYZE — 타임존 시트** | `sheet:'tz'` | dim + 바텀시트, "이 여행, 파리 기준이 맞죠?", 본문, 버튼 2개 | 6 |
| 8 | **MAP — 지도 셸** | `screen:'map'` | 실제 지도(정적 카메라), 위성 스크림, 상단바(뒤로·제목 pill·지도/위성 세그먼트), "위치 미상 5" 칩 | 7 |
| 9 | **MAP — 타임라인 스크러버** | 하단 시트 내부 | 6개 도트 + 연결선, 활성 도트 확대, AI 도트 점선 테두리 | 8 |
| 10 | **MAP — 하단 시트** | `screen:'map'` + `notCinema` | 핸들, 사진 배너 180dp(톤 배경·스크림·GPS/근사 배지·"빼기"·이름·"시각 · N/6번째"·"+N장"), 스크러버, 컨트롤(상영 모드·이전·재생/일시정지 58dp·다음), 속도 프리셋 3종 | 9 |
| 11 | **MAP — 상영 모드** | `cinema` | 전면 오버레이, 88% 카드 340dp, 이름 + "시각 · 파리", 힌트 pill | 10 |
| 12 | **MAP — 위치 미상 드로어** | `sheet:'unknown'` | dim + 바텀시트, "위치 미상 · 5장", 본문, 4열 썸네일 5개, "닫기" | 11 |

> **공용 지도 레이어**(프로토타입 `mapVisible = analyze || map`)의 SVG 지도/위성 배경·핀·경로선은 **이식하지 않는다.** 네이티브는 Google Maps SDK가 대신하며(태스크 7), 핀·경로·리플레이는 로직 단계(에픽 12~14) 소관이다. ANALYZE 화면은 이 단계에서 지도 배경 없이 `bg_base` 위에 뜬다.

### 프로토타입 원본 데이터 (픽스처가 그대로 재현할 값)

**STOPS (6개, `STOPS()` + `TONES()`):**

| id | name | time | src | extra | tone |
|---|---|---|---|---|---|
| arc | 개선문 | 10:12 | gps | 0 | #D9C9A8 |
| eiffel | 에펠탑 | 11:05 | gps | 4 | #B7C6D6 |
| seine | 센강 유람선 | 13:20 | gps | 2 | #A9C6DA |
| louvre | 루브르 박물관 | 15:40 | **ai** | 0 | #CDBFA1 |
| notredame | 노트르담 | 16:50 | gps | 0 | #C3B69B |
| sacre | 몽마르트 | 18:30 | gps | 3 | #D7D0BF |

**GRIDMETA (18타일, `GRIDMETA` + `state.grid`):** 순서대로 tone / label / 초기 선택 여부 —
`#DBE4EE`(—,✓) `#E8E0D6`(개선문,✓) `#DDE8E1`(—,✓) `#E6DDE6`(—,✓) `#E7E1D6`(음식,✗) `#D8E1EA`(에펠탑,✓) `#DFE7EC`(—,✓) `#E4E8E0`(—,✓) `#E8E2DA`(—,✓) `#D9E3EC`(센강,✓) `#E3DDE6`(—,✓) `#EAE4DA`(실내,✗) `#DDE6E8`(—,✓) `#E6E0D8`(루브르,✓) `#DCE5EE`(—,✓) `#E7E2DD`(—,✓) `#DDE8E3`(몽마르트,✓) `#E4DEE6`(—,✓)
→ 초기 선택 16장 (전체 18 중 index 4·11 해제).

**위치 미상 드로어 썸네일 5개:** `#E3D6C8` `#D6DEE6` `#E0DCE4` `#DDE6DF` `#E6DDD4`

---

## File Structure

**신규 — 화면 공통**
- `ui/preview/ScreenFixtures.java` — 위 표의 프로토타입 값을 그대로 담은 정적 픽스처. **로직 에픽에서 삭제될 임시 공급원**임을 클래스 주석에 명시.
- `ui/common/ToastPresenter.java` — 프로토타입 토스트 pill 표시.

**화면별 (`ui/<screen>/`)** — 각 패키지에 `XxxUiState` / `XxxRenderer` / `XxxFragment` / `XxxViewModel`:
- `ui/home/` — `HomeUiState`, `HomeRenderer`, `TripCardAdapter`, `HomeFragment`(신규), `HomeViewModel`(신규)
- `ui/photo/` — `PhotoSelectionUiState`, `PhotoSelectionRenderer`, `PhotoGridAdapter`, `GridSpacingDecoration`, 기존 `PhotoSelectionFragment`/`ViewModel` 개편
- `ui/analysis/` — `AnalysisUiState`, `AnalysisRenderer`, `TimezoneSheetFragment`, 기존 `AnalysisFragment`/`ViewModel` 개편
- `ui/map/` — `MapUiState`, `MapRenderer`(상단바·시트·상영모드 렌더), `TimelineScrubberView`, `UnknownPhotosSheetFragment`, 기존 `MapReplayFragment`/`ViewModel` 개편

**책임 경계:** `UiState`는 데이터만(안드로이드 타입 의존 없음 → 순수 JUnit 테스트 가능). `Renderer`는 binding+state를 받아 뷰만 변경(정적 메서드, 상태 없음 → Robolectric 테스트 대상). `Fragment`는 인플레이트·VM 관찰·클릭 위임만. `ViewModel`은 LiveData 공급원.

**레이아웃** — `fragment_home.xml`(신규), `item_trip_card.xml`(신규), `fragment_photo_selection.xml`(재작성), `item_photo_tile.xml`(신규), `fragment_analysis.xml`(재작성), `sheet_timezone.xml`(신규), `fragment_map_replay.xml`(재작성), `view_map_top_bar.xml`(신규), `view_map_bottom_sheet.xml`(신규), `view_cinema_overlay.xml`(신규), `sheet_unknown_photos.xml`(신규)

> **MAP 레이아웃 분할은 테스트 제약에서 나온다:** `fragment_map_replay.xml` 은 `SupportMapFragment` 를 담는 `FragmentContainerView` 를 가져 Robolectric 이 단독 인플레이트할 수 없다. 따라서 MAP 크롬은 `view_map_*` / `view_cinema_overlay` 로 분리해 `<include>` 하고, Renderer 테스트는 그 include 레이아웃만 인플레이트한다. 토스트 pill 은 별도 레이아웃이 아니라 이를 쓰는 각 화면 레이아웃(`fragment_home` / `fragment_analysis` / `fragment_map_replay`)에 `@id/toastPill` 로 직접 둔다 — `ToastPresenter` 가 루트에서 그 id 를 찾는다.

**리소스 추가** — `drawable/` 신규: 아이콘 `ic_add` · `ic_chevron_right` · `ic_cinema`; 일러스트 `hero_trip_paris` · `hero_trip_jeju` · `illust_empty_trips`; 배경 `bg_toast_pill` · `bg_pill_glass` · `bg_label_pill` · `bg_check_badge` · `bg_progress_card` · `bg_circle_success` · `bg_circle_glass` · `bg_pill_segment` · `bg_segment_selected` · `bg_chip_unknown` · `bg_scrim_satellite` · `bg_map_sheet` · `bg_pill_white` · `bg_circle_play` · `bg_speed_selected` · `bg_scrim_photo_banner` · `bg_cinema_hint`. `values/`: `strings.xml` · `dimens.xml` · `colors.xml` · `styles.xml` 확장.

> 사진 그리드 타일의 선택 링과 위치 미상 썸네일은 드로어블 리소스로 고정하지 않는다 — 톤 색이 항목마다 달라 `MaterialCardView.setCardBackgroundColor`/`setStroke*` 와 `GradientDrawable` 로 코드에서 지정한다.

**테스트** — `app/src/test/java/com/traveltrace/app/`: `HarnessSmokeTest`, `ui/preview/ScreenFixturesTest`(순수 JUnit), `ui/home/HomeRendererTest`, `ui/photo/PhotoSelectionRendererTest`, `ui/analysis/AnalysisRendererTest`, `ui/analysis/TimezoneSheetTest`, `ui/map/MapTopBarRendererTest`, `ui/map/TimelineScrubberViewTest`, `ui/map/MapSheetRendererTest`, `ui/map/CinemaOverlayRendererTest`, `ui/map/UnknownPhotosSheetTest`.

---

### Task 1: Robolectric 렌더 테스트 하네스

Renderer를 JVM에서 검증할 수 있어야 이후 모든 화면 태스크가 red-green 사이클을 돈다. 현재 `testImplementation`은 `junit`뿐이다.

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle`
- Test: `android/app/src/test/java/com/traveltrace/app/HarnessSmokeTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: Robolectric 테스트 실행 환경. 이후 모든 Renderer 테스트가 `@RunWith(RobolectricTestRunner.class)` + `ApplicationProvider.getApplicationContext()` + `ctx.setTheme(R.style.Theme_TravelTrace)` 패턴을 사용한다.

- [ ] **Step 1: 버전 카탈로그에 Robolectric 추가**

`android/gradle/libs.versions.toml` — `[versions]`의 `junit = "4.13.2"` 아래에 추가:

```toml
robolectric = "4.14.1"
androidxTestCore = "1.6.1"
```

`[libraries]`의 `junit = ...` 줄 아래에 추가:

```toml
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
```

- [ ] **Step 2: build.gradle에 테스트 의존성 + 리소스 접근 활성화**

`android/app/build.gradle`의 `buildFeatures { ... }` 블록 **바로 아래**에 추가 (같은 `android { }` 안):

```groovy
    // Robolectric 이 레이아웃/스타일/문자열 리소스를 인플레이트하려면 필요.
    testOptions {
        unitTests {
            includeAndroidResources = true
        }
    }
```

`dependencies { ... }`의 `testImplementation libs.junit` 줄을 다음으로 교체:

```groovy
    testImplementation libs.junit
    testImplementation libs.robolectric
    testImplementation libs.androidx.test.core
```

- [ ] **Step 3: 실패하는 스모크 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/HarnessSmokeTest.java` (신규):

```java
package com.traveltrace.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 하네스 스모크: Robolectric 이 앱 테마로 레이아웃을 인플레이트하고
 * 색 리소스를 해석할 수 있는지 확인한다. 화면 Renderer 테스트의 전제.
 *
 * <p>일부러 map 레이아웃을 쓰지 않는다 — Task 7 이후 그 레이아웃은 SupportMapFragment 를
 * 담는 FragmentContainerView 를 갖게 되고, FragmentContainerView 는 FragmentManager 밖에서
 * 인플레이트하면 예외를 던진다.
 */
@RunWith(RobolectricTestRunner.class)
public class HarnessSmokeTest {

    @Test
    public void inflatesLayoutWithAppTheme() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);

        FragmentPhotoSelectionBinding binding =
                FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(ctx));

        assertNotNull(binding.getRoot());
    }

    @Test
    public void resolvesSemanticColorTokens() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // colors.xml 의 fill_brand → palette_blue_500 → #3182F6
        assertEquals(0xFF3182F6, ctx.getColor(R.color.fill_brand));
    }
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.HarnessSmokeTest"`
Expected: Step 1~2를 아직 적용하지 않았다면 컴파일 실패(`package org.robolectric does not exist`). Step 1~2 적용 후에는 이 단계가 곧바로 PASS 한다 — 하네스 태스크의 red는 "의존성 부재"다.

> **CLI 빌드 주의**: `GRADLE_USER_HOME`을 ASCII 경로로 지정하고(한글 사용자명 → Gradle 워커 크래시), Android Studio의 JBR을 `JAVA_HOME`으로 쓴다. 예:
> `GRADLE_USER_HOME=C:/gradle-home JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.HarnessSmokeTest"`
Expected: PASS (2 tests). 최초 실행은 Robolectric 이 android-all jar 를 내려받아 수 분 걸릴 수 있다.

- [ ] **Step 6: 커밋**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle android/app/src/test/java/com/traveltrace/app/HarnessSmokeTest.java
git commit -m "test: add Robolectric render-test harness"
```

---

### Task 2: 화면 UiState 모델 + 프로토타입 픽스처

모든 화면이 소비할 불변 데이터와 그 공급원. 안드로이드 타입에 의존하지 않으므로 순수 JUnit으로 검증한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeUiState.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionUiState.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisUiState.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/preview/ScreenFixturesTest.java`

**Interfaces:**
- Consumes: Task 1의 테스트 하네스 (이 태스크 자체는 순수 JUnit이라 Robolectric 불필요)
- Produces:
  - `HomeUiState.trips(List<TripCard>)` / `HomeUiState.empty()` → `HomeUiState`; 필드 `boolean empty`, `List<HomeUiState.TripCard> trips`. `TripCard(String id, String title, String meta, String locationLabel, boolean enabled)` — **드로어블 리소스 ID를 담지 않는다.** hero 일러스트는 `id`("paris"/"jeju")를 보고 `TripCardAdapter`(Task 3)가 고른다: UiState 는 리소스에 의존하지 않아야 순수 JUnit으로 테스트되고, 로직 단계에서 실제 사진으로 바뀔 때도 형태가 유지된다.
  - `PhotoSelectionUiState(String periodLabel, int maxCount, List<Tile> tiles)`; `Tile(int toneColor, String label, boolean selected)`; 메서드 `int selectedCount()`, `PhotoSelectionUiState withToggled(int index)`.
  - `AnalysisUiState(int analyzed, int total, boolean done, String recognizedName, int routeCount, int unknownCount)`; 메서드 `int progressPercent()`.
  - `MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex, boolean playing, boolean satellite, boolean cinema, Speed speed)`; `Stop(String id, String name, String time, boolean ai, int extra, int toneColor)`; `enum Speed { RELAXED, NORMAL, FAST }`; 메서드 `Stop activeStop()`.
  - `ScreenFixtures.home()` / `.homeEmpty()` / `.photoSelection()` / `.analysisInProgress(int analyzed)` / `.analysisDone()` / `.map()` / `.unknownThumbTones()`.

- [ ] **Step 1: 실패하는 픽스처 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/preview/ScreenFixturesTest.java` (신규):

```java
package com.traveltrace.app.ui.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.traveltrace.app.ui.analysis.AnalysisUiState;
import com.traveltrace.app.ui.home.HomeUiState;
import com.traveltrace.app.ui.map.MapUiState;
import com.traveltrace.app.ui.photo.PhotoSelectionUiState;

import org.junit.Test;

/** 픽스처가 프로토타입 원본 값과 어긋나면 화면 충실도가 조용히 깨지므로 값을 고정한다. */
public class ScreenFixturesTest {

    @Test
    public void home_hasParisEnabledAndJejuLocked() {
        HomeUiState s = ScreenFixtures.home();
        assertFalse(s.empty);
        assertEquals(2, s.trips.size());

        HomeUiState.TripCard paris = s.trips.get(0);
        assertEquals("paris", paris.id);
        assertEquals("2024 파리 여행", paris.title);
        assertEquals("82장 · 4일 · 2024. 6", paris.meta);
        assertEquals("🇫🇷 파리 · 프랑스", paris.locationLabel);
        assertTrue(paris.enabled);

        HomeUiState.TripCard jeju = s.trips.get(1);
        assertEquals("2023 제주 가족여행", jeju.title);
        assertEquals("63장 · 3일 · 2023. 10", jeju.meta);
        assertFalse("제주 카드는 데모에서 잠김", jeju.enabled);
    }

    @Test
    public void homeEmpty_hasNoTrips() {
        HomeUiState s = ScreenFixtures.homeEmpty();
        assertTrue(s.empty);
        assertTrue(s.trips.isEmpty());
    }

    @Test
    public void photoSelection_has18TilesWith16Selected() {
        PhotoSelectionUiState s = ScreenFixtures.photoSelection();
        assertEquals(18, s.tiles.size());
        assertEquals(16, s.selectedCount());
        assertEquals(100, s.maxCount);
        assertEquals("2024. 6. 12 – 6. 15 · 사진 94장", s.periodLabel);

        // index 4(음식) / 11(실내) 만 초기 해제 — 프로토타입 state.grid
        assertFalse(s.tiles.get(4).selected);
        assertFalse(s.tiles.get(11).selected);
        assertEquals("음식", s.tiles.get(4).label);
        assertEquals(0xFFDBE4EE, s.tiles.get(0).toneColor);
        assertNull("라벨 없는 타일은 null", s.tiles.get(0).label);
    }

    @Test
    public void photoSelection_toggleFlipsOneTileImmutably() {
        PhotoSelectionUiState s = ScreenFixtures.photoSelection();
        PhotoSelectionUiState next = s.withToggled(0);

        assertTrue("원본 불변", s.tiles.get(0).selected);
        assertFalse(next.tiles.get(0).selected);
        assertEquals(15, next.selectedCount());
    }

    @Test
    public void analysisInProgress_reportsPercentAgainst82() {
        AnalysisUiState s = ScreenFixtures.analysisInProgress(41);
        assertEquals(41, s.analyzed);
        assertEquals(82, s.total);
        assertFalse(s.done);
        assertEquals(50, s.progressPercent());
    }

    @Test
    public void analysisDone_reportsRouteAndUnknownCounts() {
        AnalysisUiState s = ScreenFixtures.analysisDone();
        assertTrue(s.done);
        assertEquals(82, s.analyzed);
        assertEquals(100, s.progressPercent());
        assertEquals(6, s.routeCount);
        assertEquals(5, s.unknownCount);
    }

    @Test
    public void map_hasSixStopsWithLouvreAsAi() {
        MapUiState s = ScreenFixtures.map();
        assertEquals("2024 파리 여행", s.tripTitle);
        assertEquals(5, s.unknownCount);
        assertEquals(6, s.stops.size());
        assertEquals(0, s.activeIndex);
        assertEquals(MapUiState.Speed.NORMAL, s.speed);

        MapUiState.Stop arc = s.stops.get(0);
        assertEquals("개선문", arc.name);
        assertEquals("10:12", arc.time);
        assertFalse(arc.ai);
        assertEquals(0xFFD9C9A8, arc.toneColor);

        MapUiState.Stop louvre = s.stops.get(3);
        assertEquals("루브르 박물관", louvre.name);
        assertTrue("루브르만 AI 근사 위치", louvre.ai);

        assertEquals(4, s.stops.get(1).extra); // 에펠탑 +4장
        assertEquals("개선문", s.activeStop().name);
    }

    @Test
    public void unknownThumbTones_hasFiveTones() {
        assertEquals(5, ScreenFixtures.unknownThumbTones().length);
        assertEquals(0xFFE3D6C8, ScreenFixtures.unknownThumbTones()[0]);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.preview.ScreenFixturesTest"`
Expected: 컴파일 실패 — `package com.traveltrace.app.ui.preview does not exist`, `cannot find symbol: class HomeUiState`.

- [ ] **Step 3: HomeUiState 작성**

`android/app/src/main/java/com/traveltrace/app/ui/home/HomeUiState.java` (신규):

```java
package com.traveltrace.app.ui.home;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/** HOME 화면이 렌더할 불변 상태. 리소스 ID를 담지 않는다 — 순수 JUnit 테스트 대상. */
public final class HomeUiState {

    public final boolean empty;
    public final List<TripCard> trips;

    private HomeUiState(boolean empty, List<TripCard> trips) {
        this.empty = empty;
        this.trips = Collections.unmodifiableList(trips);
    }

    public static HomeUiState trips(List<TripCard> trips) {
        return new HomeUiState(false, trips);
    }

    public static HomeUiState empty() {
        return new HomeUiState(true, Collections.<TripCard>emptyList());
    }

    /** 여행 카드 1장. 카드 상단 140dp hero 일러스트는 TripCardAdapter 가 id 로 고른다. */
    public static final class TripCard {
        public final String id;
        public final String title;
        public final String meta;
        @Nullable public final String locationLabel;
        /** false 면 탭 시 토스트만 띄운다 (프로토타입 openTripLocked). */
        public final boolean enabled;

        public TripCard(String id, String title, String meta, @Nullable String locationLabel,
                        boolean enabled) {
            this.id = id;
            this.title = title;
            this.meta = meta;
            this.locationLabel = locationLabel;
            this.enabled = enabled;
        }
    }
}
```

- [ ] **Step 4: PhotoSelectionUiState 작성**

`android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionUiState.java` (신규):

```java
package com.traveltrace.app.ui.photo;

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
        this.tiles = Collections.unmodifiableList(tiles);
    }

    public int selectedCount() {
        int n = 0;
        for (Tile t : tiles) {
            if (t.selected) n++;
        }
        return n;
    }

    /** index 타일의 선택 상태만 뒤집은 새 상태를 만든다 (원본 불변). */
    public PhotoSelectionUiState withToggled(int index) {
        List<Tile> next = new ArrayList<>(tiles);
        Tile t = next.get(index);
        next.set(index, new Tile(t.toneColor, t.label, !t.selected));
        return new PhotoSelectionUiState(periodLabel, maxCount, next);
    }

    /** 그리드 타일 1개. 실제 사진은 로직 단계에서 붙고, 지금은 톤 색으로 대체한다. */
    public static final class Tile {
        @ColorInt public final int toneColor;
        /** 라벨 pill 문구. 없으면 null. */
        @Nullable public final String label;
        public final boolean selected;

        public Tile(@ColorInt int toneColor, @Nullable String label, boolean selected) {
            this.toneColor = toneColor;
            this.label = label;
            this.selected = selected;
        }
    }
}
```

- [ ] **Step 5: AnalysisUiState 작성**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisUiState.java` (신규):

```java
package com.traveltrace.app.ui.analysis;

/** ANALYZE 화면이 렌더할 불변 상태. done 이면 완료형 카드 + CTA 로 전환된다. */
public final class AnalysisUiState {

    public final int analyzed;
    public final int total;
    public final boolean done;
    public final String recognizedName;
    public final int routeCount;
    public final int unknownCount;

    public AnalysisUiState(int analyzed, int total, boolean done, String recognizedName,
                           int routeCount, int unknownCount) {
        this.analyzed = analyzed;
        this.total = total;
        this.done = done;
        this.recognizedName = recognizedName;
        this.routeCount = routeCount;
        this.unknownCount = unknownCount;
    }

    public int progressPercent() {
        if (total <= 0) return 0;
        return Math.round(analyzed * 100f / total);
    }
}
```

- [ ] **Step 6: MapUiState 작성**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapUiState.java` (신규):

```java
package com.traveltrace.app.ui.map;

import androidx.annotation.ColorInt;

import java.util.Collections;
import java.util.List;

/** MAP 화면(상단바·하단시트·상영모드)이 렌더할 불변 상태. */
public final class MapUiState {

    /** 리플레이 속도 프리셋 (프로토타입 느긋이/보통/빠르게). */
    public enum Speed { RELAXED, NORMAL, FAST }

    public final String tripTitle;
    public final int unknownCount;
    public final List<Stop> stops;
    public final int activeIndex;
    public final boolean playing;
    public final boolean satellite;
    public final boolean cinema;
    public final Speed speed;

    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed) {
        this.tripTitle = tripTitle;
        this.unknownCount = unknownCount;
        this.stops = Collections.unmodifiableList(stops);
        this.activeIndex = activeIndex;
        this.playing = playing;
        this.satellite = satellite;
        this.cinema = cinema;
        this.speed = speed;
    }

    public Stop activeStop() {
        return stops.get(Math.min(activeIndex, stops.size() - 1));
    }

    /** 경로 위 정차 지점 1곳. 좌표는 로직 단계(에픽 12)에서 붙는다. */
    public static final class Stop {
        public final String id;
        public final String name;
        public final String time;
        /** true 면 AI 근사 위치 (프로토타입 src:'ai') — 배지·점선 표식 대상. */
        public final boolean ai;
        /** 같은 지점의 추가 사진 수 ("+N장"). 0 이면 숨김. */
        public final int extra;
        @ColorInt public final int toneColor;

        public Stop(String id, String name, String time, boolean ai, int extra,
                    @ColorInt int toneColor) {
            this.id = id;
            this.name = name;
            this.time = time;
            this.ai = ai;
            this.extra = extra;
            this.toneColor = toneColor;
        }
    }
}
```

- [ ] **Step 7: ScreenFixtures 작성**

`android/app/src/main/java/com/traveltrace/app/ui/preview/ScreenFixtures.java` (신규):

```java
package com.traveltrace.app.ui.preview;

import com.traveltrace.app.ui.analysis.AnalysisUiState;
import com.traveltrace.app.ui.home.HomeUiState;
import com.traveltrace.app.ui.map.MapUiState;
import com.traveltrace.app.ui.photo.PhotoSelectionUiState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 화면-우선 구현 단계의 임시 데이터 공급원. 값은 프로토타입
 * (prototype/TravelTrace.html 의 STOPS()/TONES()/GRIDMETA/state.grid)과 1:1로 일치한다.
 *
 * <p>로직 에픽에서 각 ViewModel 의 공급원이 Repository 로 교체되면 이 클래스는 삭제된다.
 * Renderer·레이아웃은 그때도 바뀌지 않는다 — UiState 가 경계다.
 */
public final class ScreenFixtures {

    private ScreenFixtures() {}

    private static final int TOTAL_PHOTOS = 82;

    // ---- HOME ----

    public static HomeUiState home() {
        List<HomeUiState.TripCard> trips = new ArrayList<>();
        trips.add(new HomeUiState.TripCard(
                "paris", "2024 파리 여행", "82장 · 4일 · 2024. 6",
                "🇫🇷 파리 · 프랑스", true));
        // 제주 카드는 프로토타입에서 시각 전용(openTripLocked → 토스트).
        trips.add(new HomeUiState.TripCard(
                "jeju", "2023 제주 가족여행", "63장 · 3일 · 2023. 10",
                "🌋 제주 · 한국", false));
        return HomeUiState.trips(trips);
    }

    public static HomeUiState homeEmpty() {
        return HomeUiState.empty();
    }

    // ---- SELECT ----

    /** GRIDMETA 18타일: {tone, label(nullable), 초기 선택}. index 4·11 만 해제. */
    public static PhotoSelectionUiState photoSelection() {
        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        tiles.add(tile(0xFFDBE4EE, null, true));
        tiles.add(tile(0xFFE8E0D6, "개선문", true));
        tiles.add(tile(0xFFDDE8E1, null, true));
        tiles.add(tile(0xFFE6DDE6, null, true));
        tiles.add(tile(0xFFE7E1D6, "음식", false));
        tiles.add(tile(0xFFD8E1EA, "에펠탑", true));
        tiles.add(tile(0xFFDFE7EC, null, true));
        tiles.add(tile(0xFFE4E8E0, null, true));
        tiles.add(tile(0xFFE8E2DA, null, true));
        tiles.add(tile(0xFFD9E3EC, "센강", true));
        tiles.add(tile(0xFFE3DDE6, null, true));
        tiles.add(tile(0xFFEAE4DA, "실내", false));
        tiles.add(tile(0xFFDDE6E8, null, true));
        tiles.add(tile(0xFFE6E0D8, "루브르", true));
        tiles.add(tile(0xFFDCE5EE, null, true));
        tiles.add(tile(0xFFE7E2DD, null, true));
        tiles.add(tile(0xFFDDE8E3, "몽마르트", true));
        tiles.add(tile(0xFFE4DEE6, null, true));
        return new PhotoSelectionUiState("2024. 6. 12 – 6. 15 · 사진 94장", 100, tiles);
    }

    private static PhotoSelectionUiState.Tile tile(int tone, String label, boolean selected) {
        return new PhotoSelectionUiState.Tile(tone, label, selected);
    }

    // ---- ANALYZE ----

    public static AnalysisUiState analysisInProgress(int analyzed) {
        return new AnalysisUiState(analyzed, TOTAL_PHOTOS, false, recognizedAt(analyzed), 0, 0);
    }

    public static AnalysisUiState analysisDone() {
        return new AnalysisUiState(TOTAL_PHOTOS, TOTAL_PHOTOS, true, "몽마르트", 6, 5);
    }

    /**
     * 프로토타입 recogName(): analyzed 가 각 정차점의 lit 임계치를 넘을 때마다
     * 마지막으로 밝혀진 장소 이름을 보여준다. lit = {8, 22, 38, 54, 68, 80}.
     */
    private static String recognizedAt(int analyzed) {
        int[] lit = {8, 22, 38, 54, 68, 80};
        String[] names = {"개선문", "에펠탑", "센강 유람선", "루브르 박물관", "노트르담", "몽마르트"};
        String current = "사진 읽는 중";
        for (int i = 0; i < lit.length; i++) {
            if (analyzed >= lit[i]) current = names[i];
        }
        return current;
    }

    // ---- MAP ----

    public static MapUiState map() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 4, 0xFFB7C6D6));
        stops.add(new MapUiState.Stop("seine", "센강 유람선", "13:20", false, 2, 0xFFA9C6DA));
        stops.add(new MapUiState.Stop("louvre", "루브르 박물관", "15:40", true, 0, 0xFFCDBFA1));
        stops.add(new MapUiState.Stop("notredame", "노트르담", "16:50", false, 0, 0xFFC3B69B));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 3, 0xFFD7D0BF));
        return new MapUiState("2024 파리 여행", 5, stops, 0, false, false, false,
                MapUiState.Speed.NORMAL);
    }

    /** 위치 미상 드로어의 썸네일 5개 톤. */
    public static int[] unknownThumbTones() {
        return new int[]{0xFFE3D6C8, 0xFFD6DEE6, 0xFFE0DCE4, 0xFFDDE6DF, 0xFFE6DDD4};
    }

    /** 상영 모드 카드의 도시 라벨 (프로토타입 "시각 · 파리"). */
    public static String cityLabel() {
        return "파리";
    }

    /** 테스트/디버그 편의용 — 정차점 이름 목록. */
    public static List<String> stopNames() {
        return Arrays.asList("개선문", "에펠탑", "센강 유람선", "루브르 박물관", "노트르담", "몽마르트");
    }
}
```

- [ ] **Step 8: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.preview.ScreenFixturesTest"`
Expected: PASS (8 tests).

- [ ] **Step 9: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/ android/app/src/test/java/com/traveltrace/app/ui/
git commit -m "feat: add screen UiState models and prototype fixtures"
```

---

### Task 3: HOME — 여행 목록 / 빈 상태 / CTA / 토스트

프로토타입 `screen:'home'`의 두 상태와, 잠긴 카드 탭 시 뜨는 토스트까지. 앱의 새 시작 지점이 된다.

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/drawable/ic_add.xml`
- Create: `android/app/src/main/res/drawable/hero_trip_paris.xml`
- Create: `android/app/src/main/res/drawable/hero_trip_jeju.xml`
- Create: `android/app/src/main/res/drawable/illust_empty_trips.xml`
- Create: `android/app/src/main/res/drawable/bg_toast_pill.xml`
- Create: `android/app/src/main/res/drawable/bg_pill_glass.xml`
- Create: `android/app/src/main/res/layout/fragment_home.xml`
- Create: `android/app/src/main/res/layout/item_trip_card.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/common/ToastPresenter.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/home/TripCardAdapter.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeRenderer.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeViewModel.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/home/HomeFragment.java`
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/home/HomeRendererTest.java`

**Interfaces:**
- Consumes: `HomeUiState` / `HomeUiState.TripCard` / `ScreenFixtures.home()` / `ScreenFixtures.homeEmpty()` (Task 2), Robolectric 하네스 (Task 1)
- Produces:
  - `HomeRenderer.render(FragmentHomeBinding binding, HomeUiState state, TripCardAdapter.Listener listener)` — static void. 목록/빈 상태 전환 + 어댑터 생성·제출.
  - `TripCardAdapter(TripCardAdapter.Listener listener)`; `void submit(List<HomeUiState.TripCard>)`; `interface Listener { void onTripClick(HomeUiState.TripCard card); }`. hero 일러스트는 `card.id`로 고른다("paris"→`hero_trip_paris`, 그 외→`hero_trip_jeju`).
  - `ToastPresenter.show(View anchorRoot, String message)` — static void. 프로토타입 토스트 pill(하단 110dp, 1.9초)을 띄운다. 이후 태스크 9(핀 빼기 토스트)가 재사용한다.
  - `HomeViewModel.state()` → `LiveData<HomeUiState>`; `void showEmpty(boolean)` (디버그 토글용).
  - nav_graph: `@id/homeFragment` 가 `app:startDestination`. 액션 `@id/action_home_to_photo`, `@id/action_home_to_map`.

- [ ] **Step 1: RecyclerView 의존성 명시**

여행 카드 목록·사진 그리드(Task 4)가 RecyclerView를 쓴다. 지금은 `material` 을 통해 **전이적으로만** 들어와 있어 직접 참조가 우연에 기댄다 — 명시적으로 선언한다.

`android/gradle/libs.versions.toml` — `[versions]` 에 추가:

```toml
recyclerview = "1.3.2"
```

`[libraries]` 에 추가:

```toml
androidx-recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }
```

`android/app/build.gradle` 의 `dependencies { ... }` — `implementation libs.androidx.constraintlayout` 아래에 추가:

```groovy
    implementation libs.androidx.recyclerview
```

- [ ] **Step 2: 문자열 추가**

`android/app/src/main/res/values/strings.xml` — 기존 `<resources>` 안, 스캐폴딩 문자열 아래에 추가:

```xml
    <!-- HOME -->
    <string name="home_title">여행</string>
    <string name="home_subtitle">다녀온 길을 다시 떠나요</string>
    <string name="home_settings_desc">설정</string>
    <string name="home_new_trip">새 여행 만들기</string>
    <string name="home_empty_title">첫 여행을 만들어 보세요</string>
    <string name="home_empty_body">갤러리에서 여행 사진을 고르면\n지도 위에 그날의 경로가 그려져요.</string>
    <string name="home_trip_locked_toast">데모에선 파리 여행만 열려요</string>
    <string name="home_trip_hero_desc">여행 경로 미리보기</string>
```

- [ ] **Step 3: 치수 추가**

`android/app/src/main/res/values/dimens.xml` — `<!-- Component -->` 섹션 끝(`sheet_handle_height` 아래)에 추가:

```xml
    <!-- 완전한 라운드(pill). 기존 Radii 목록에 없고 badge_*.xml 들은 999dp 를 하드코딩해
         왔다 — 이 계획의 pill 드로어블 8종이 공유하므로 토큰으로 승격한다. -->
    <dimen name="radius_full">999dp</dimen>

    <!-- 프로토타입 실측: 토큰 스케일에 없는 화면 고유 치수 -->
    <dimen name="text_home_title">27sp</dimen>
    <dimen name="line_home_title">30sp</dimen>   <!-- 27 x1.1 -->
    <dimen name="text_card_title">18sp</dimen>
    <dimen name="line_card_title">22sp</dimen>
    <dimen name="text_empty_title">21sp</dimen>
    <dimen name="line_empty_title">27sp</dimen>

    <dimen name="home_header_icon">40dp</dimen>
    <dimen name="trip_hero_height">140dp</dimen>
    <dimen name="cta_fade_height">120dp</dimen>
    <dimen name="toast_bottom_margin">110dp</dimen>
```

- [ ] **Step 4: 일러스트 전용 색 추가**

`android/app/src/main/res/values/colors.xml` — `</resources>` 바로 위에 추가. (배지 전용 반투명 색과 같은 선례: 화면 고유 실측 색은 시맨틱 섹션으로 분리한다.)

```xml
    <!-- 일러스트 전용 (프로토타입 SVG 실측). 화면 텍스트/배경엔 쓰지 않는다. -->
    <color name="illust_hero_bg_start">#EEF2F6</color>
    <color name="illust_hero_bg_end">#E3EBF3</color>
    <color name="illust_route">#CFE0F0</color>
    <color name="illust_road">#DDE4EA</color>
    <color name="illust_jeju_bg_start">#EEF4F0</color>
    <color name="illust_jeju_bg_end">#E2EFE8</color>
    <color name="illust_jeju_land">#D7ECE0</color>
    <color name="illust_empty_disc">#EEF2F6</color>
    <color name="illust_empty_path">#CDD9E6</color>

    <!-- 토스트 pill: rgba(25,31,40,.92) -->
    <color name="toast_pill_bg">#EB191F28</color>
    <!-- hero 위 위치 라벨 pill: rgba(255,255,255,.72) — 실제 blur 는 post-v1, 불투명 폴백 -->
    <color name="pill_glass_bg">#B8FFFFFF</color>
```

- [ ] **Step 5: 아이콘·배경 드로어블 작성**

`android/app/src/main/res/drawable/ic_add.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- CTA 플러스 아이콘 (프로토타입 24x24 path). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="22dp"
    android:height="22dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="@color/text_on_fill">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,3.75a0.75,0.75 0,0 1,0.75 0.75v6.75h6.75a0.75,0.75 0,0 1,0 1.5H12.75v6.75a0.75,0.75 0,0 1,-1.5 0V12.75H4.5a0.75,0.75 0,0 1,0 -1.5h6.75V4.5a0.75,0.75 0,0 1,0.75 -0.75Z" />
</vector>
```

`android/app/src/main/res/drawable/bg_toast_pill.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 토스트 pill 배경: radius 12dp, rgba(25,31,40,.92). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/toast_pill_bg" />
    <corners android:radius="@dimen/radius_12" />
</shape>
```

`android/app/src/main/res/drawable/bg_pill_glass.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  hero 위 위치 라벨 pill. 프로토타입은 반투명 흰색 + backdrop-filter:blur(6px).
  실제 blur 는 post-v1 — 불투명도를 높인 스크림으로 폴백한다(pill_location_scrim 선례).
-->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/pill_glass_bg" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

- [ ] **Step 6: 일러스트 드로어블 3종 작성**

`android/app/src/main/res/drawable/hero_trip_paris.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  파리 카드 hero (프로토타입 SVG viewBox 0 0 320 140 이식).
  알려진 격차: VectorDrawable 은 stroke-dasharray 를 지원하지 않으므로
  AI 근사 지점(186,78)의 점선 링은 실선 회색 링으로 대체한다.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="320dp"
    android:height="140dp"
    android:viewportWidth="320"
    android:viewportHeight="140">

    <!-- 배경 그라데이션 (CSS 135deg: 좌상 → 우하) -->
    <path android:pathData="M0,0 L320,0 L320,140 L0,140 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="320" android:endY="140"
                android:startColor="@color/illust_hero_bg_start"
                android:endColor="@color/illust_hero_bg_end" />
        </aapt:attr>
    </path>

    <!-- 강(굵은 곡선) -->
    <path
        android:pathData="M-10,96 C60,80 110,110 175,70 C230,38 270,60 330,30"
        android:strokeColor="@color/illust_route"
        android:strokeWidth="14"
        android:strokeAlpha="0.7"
        android:strokeLineCap="round" />

    <!-- 도로 2줄 -->
    <path android:pathData="M40,120 L300,24" android:strokeColor="@color/illust_road" android:strokeWidth="2" />
    <path android:pathData="M20,50 L260,130" android:strokeColor="@color/illust_road" android:strokeWidth="2" />

    <!-- 경로 폴리라인 -->
    <path
        android:pathData="M64,110 L120,60 L186,78 L232,44 L286,58"
        android:strokeColor="@color/fill_brand"
        android:strokeWidth="2.5"
        android:strokeLineJoin="round"
        android:strokeLineCap="round" />

    <!-- 경로 위 지점 5개 (r=5) -->
    <path android:fillColor="@color/fill_brand" android:pathData="M64,105 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M120,55 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M186,73 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M232,39 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M286,53 a5,5 0 1,0 0.1,0 Z" />

    <!-- AI 근사 지점 링 (원본은 점선 — 위 주석의 격차) -->
    <path
        android:pathData="M186,73 a5,5 0 1,0 0.1,0 Z"
        android:strokeColor="@color/location_approx"
        android:strokeWidth="2.5" />
</vector>
```

`android/app/src/main/res/drawable/hero_trip_jeju.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 제주 카드 hero (프로토타입 SVG viewBox 0 0 320 140 이식). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="320dp"
    android:height="140dp"
    android:viewportWidth="320"
    android:viewportHeight="140">

    <path android:pathData="M0,0 L320,0 L320,140 L0,140 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="320" android:endY="140"
                android:startColor="@color/illust_jeju_bg_start"
                android:endColor="@color/illust_jeju_bg_end" />
        </aapt:attr>
    </path>

    <!-- 섬 타원 (cx160 cy80 rx150 ry50, opacity .6) -->
    <path
        android:fillColor="@color/illust_jeju_land"
        android:fillAlpha="0.6"
        android:pathData="M10,80 a150,50 0 1,0 300,0 a150,50 0 1,0 -300,0 Z" />

    <path
        android:pathData="M40,70 L110,90 L180,56 L250,84"
        android:strokeColor="@color/fill_brand"
        android:strokeWidth="2.5"
        android:strokeLineJoin="round"
        android:strokeLineCap="round" />

    <path android:fillColor="@color/fill_brand" android:pathData="M40,65 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M110,85 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M180,51 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M250,79 a5,5 0 1,0 0.1,0 Z" />
</vector>
```

`android/app/src/main/res/drawable/illust_empty_trips.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 빈 상태 일러스트 (프로토타입 SVG viewBox 0 0 120 120 이식). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="120dp"
    android:height="120dp"
    android:viewportWidth="120"
    android:viewportHeight="120">

    <!-- 배경 디스크 (cx60 cy60 r56) -->
    <path android:fillColor="@color/illust_empty_disc"
        android:pathData="M4,60 a56,56 0 1,0 112,0 a56,56 0 1,0 -112,0 Z" />

    <!-- 경로 곡선 -->
    <path
        android:pathData="M28,78 C44,70 52,84 66,64 C76,50 86,58 96,46"
        android:strokeColor="@color/illust_empty_path"
        android:strokeWidth="3"
        android:strokeLineCap="round" />

    <!-- 경로 위 지점 2개 (r=5) -->
    <path android:fillColor="@color/fill_brand" android:pathData="M44,69 a5,5 0 1,0 0.1,0 Z" />
    <path android:fillColor="@color/fill_brand" android:pathData="M78,49 a5,5 0 1,0 0.1,0 Z" />

    <!-- 핀 -->
    <path android:fillColor="@color/fill_brand"
        android:pathData="M60,30c-6.6,0 -12,5.4 -12,12 0,9 12,20 12,20s12,-11 12,-20c0,-6.6 -5.4,-12 -12,-12Z" />
    <path android:fillColor="@color/surface"
        android:pathData="M55.8,42 a4.2,4.2 0 1,0 8.4,0 a4.2,4.2 0 1,0 -8.4,0 Z" />
</vector>
```

- [ ] **Step 7: 여행 카드 아이템 레이아웃 작성**

`android/app/src/main/res/layout/item_trip_card.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 여행 카드: hero 140dp + 위치 pill + 제목/메타 (프로토타입 HOME 카드). -->
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/tripCard"
    style="@style/Widget.TravelTrace.Card"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:clickable="true"
    android:focusable="true"
    app:cardPreventCornerOverlap="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="@dimen/trip_hero_height">

            <ImageView
                android:id="@+id/tripHero"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:contentDescription="@string/home_trip_hero_desc"
                android:scaleType="centerCrop" />

            <TextView
                android:id="@+id/tripLocation"
                style="@style/Widget.TravelTrace.Badge"
                android:layout_gravity="bottom|start"
                android:layout_marginStart="14dp"
                android:layout_marginBottom="12dp"
                android:background="@drawable/bg_pill_glass"
                android:textColor="@color/text_secondary"
                android:textFontWeight="700"
                tools:text="🇫🇷 파리 · 프랑스" />
        </FrameLayout>

        <TextView
            android:id="@+id/tripTitle"
            style="@style/TextAppearance.TravelTrace.Title3"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="17dp"
            android:layout_marginTop="15dp"
            android:layout_marginEnd="17dp"
            android:textSize="@dimen/text_card_title"
            android:lineHeight="@dimen/line_card_title"
            tools:text="2024 파리 여행" />

        <TextView
            android:id="@+id/tripMeta"
            style="@style/TextAppearance.TravelTrace.Numeric"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="17dp"
            android:layout_marginTop="5dp"
            android:layout_marginEnd="17dp"
            android:layout_marginBottom="17dp"
            android:textSize="@dimen/text_caption"
            android:textColor="@color/text_tertiary"
            tools:text="82장 · 4일 · 2024. 6" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

- [ ] **Step 8: HOME 레이아웃 작성**

`android/app/src/main/res/layout/fragment_home.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- HOME: 헤더 + (여행 목록 | 빈 상태) + 플로팅 CTA + 토스트. -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/homeRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_screen"
    android:fitsSystemWindows="true">

    <TextView
        android:id="@+id/homeTitle"
        style="@style/TextAppearance.TravelTrace.Display"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="22dp"
        android:layout_marginTop="14dp"
        android:letterSpacing="-0.03"
        android:lineHeight="@dimen/line_home_title"
        android:text="@string/home_title"
        android:textSize="@dimen/text_home_title"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/homeSubtitle"
        style="@style/TextAppearance.TravelTrace.Label"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="5dp"
        android:text="@string/home_subtitle"
        android:textColor="@color/text_tertiary"
        android:textFontWeight="500"
        app:layout_constraintStart_toStartOf="@id/homeTitle"
        app:layout_constraintTop_toBottomOf="@id/homeTitle" />

    <ImageView
        android:id="@+id/homeSettings"
        android:layout_width="@dimen/home_header_icon"
        android:layout_height="@dimen/home_header_icon"
        android:layout_marginTop="14dp"
        android:layout_marginEnd="22dp"
        android:background="@drawable/bg_pill_glass"
        android:backgroundTint="@color/fill_neutral"
        android:contentDescription="@string/home_settings_desc"
        android:padding="9dp"
        android:src="@drawable/ic_settings"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:tint="@color/text_secondary" />

    <!-- 여행 목록 상태 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/tripList"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="18dp"
        android:clipToPadding="false"
        android:paddingStart="18dp"
        android:paddingEnd="18dp"
        android:paddingBottom="@dimen/cta_fade_height"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/homeSubtitle"
        tools:listitem="@layout/item_trip_card" />

    <!-- 빈 상태 -->
    <androidx.constraintlayout.widget.Group
        android:id="@+id/emptyGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:constraint_referenced_ids="emptyIllust,emptyTitle,emptyBody" />

    <ImageView
        android:id="@+id/emptyIllust"
        android:layout_width="120dp"
        android:layout_height="120dp"
        android:layout_marginTop="54dp"
        android:contentDescription="@null"
        android:src="@drawable/illust_empty_trips"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/homeSubtitle" />

    <TextView
        android:id="@+id/emptyTitle"
        style="@style/TextAppearance.TravelTrace.Title2"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="26dp"
        android:lineHeight="@dimen/line_empty_title"
        android:text="@string/home_empty_title"
        android:textSize="@dimen/text_empty_title"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/emptyIllust" />

    <TextView
        android:id="@+id/emptyBody"
        style="@style/TextAppearance.TravelTrace.Body"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="9dp"
        android:gravity="center"
        android:text="@string/home_empty_body"
        android:textColor="@color/text_tertiary"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/emptyTitle"
        app:layout_constraintWidth_max="330dp" />

    <!-- 플로팅 CTA (그라데이션 페이드 위) -->
    <View
        android:id="@+id/ctaFade"
        android:layout_width="0dp"
        android:layout_height="@dimen/cta_fade_height"
        android:background="@drawable/bg_cta_fade"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/newTripButton"
        style="@style/Widget.TravelTrace.Button.Primary"
        android:layout_width="0dp"
        android:layout_height="@dimen/btn_height_primary"
        android:layout_marginStart="18dp"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="30dp"
        android:text="@string/home_new_trip"
        app:icon="@drawable/ic_add"
        app:iconGravity="textStart"
        app:iconPadding="7dp"
        app:iconTint="@color/text_on_fill"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <!-- 토스트 pill -->
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
        app:layout_constraintStart_toStartOf="parent"
        tools:text="데모에선 파리 여행만 열려요"
        tools:visibility="visible" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 9: 실패하는 HomeRenderer 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/home/HomeRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class HomeRendererTest {

    private Context ctx;
    private FragmentHomeBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentHomeBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void tripsState_showsListAndHidesEmptyBlock() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});

        assertEquals(View.VISIBLE, binding.tripList.getVisibility());
        assertEquals(View.GONE, binding.emptyGroup.getVisibility());
        assertNotNull(binding.tripList.getAdapter());
        assertEquals(2, binding.tripList.getAdapter().getItemCount());
    }

    @Test
    public void emptyState_showsEmptyBlockAndHidesList() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        assertEquals(View.GONE, binding.tripList.getVisibility());
        assertEquals(View.VISIBLE, binding.emptyGroup.getVisibility());
    }

    @Test
    public void ctaIsAlwaysVisibleInBothStates() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());

        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());
    }

    @Test
    public void emptyBlockUsesPrototypeCopy() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        TextView title = binding.emptyTitle;
        assertEquals("첫 여행을 만들어 보세요", title.getText().toString());
    }

    @Test
    public void toastPillStartsHiddenAndShowsMessage() {
        assertEquals(View.GONE, binding.toastPill.getVisibility());

        com.traveltrace.app.ui.common.ToastPresenter.show(
                binding.getRoot(), "데모에선 파리 여행만 열려요");

        assertEquals(View.VISIBLE, binding.toastPill.getVisibility());
        assertEquals("데모에선 파리 여행만 열려요", binding.toastPill.getText().toString());
    }
}
```

- [ ] **Step 10: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.home.HomeRendererTest"`
Expected: 컴파일 실패 — `cannot find symbol: class HomeRenderer`, `cannot find symbol: class ToastPresenter`.

- [ ] **Step 11: ToastPresenter · TripCardAdapter · HomeRenderer 작성**

`android/app/src/main/java/com/traveltrace/app/ui/common/ToastPresenter.java` (신규):

```java
package com.traveltrace.app.ui.common;

import android.view.View;
import android.widget.TextView;

import com.traveltrace.app.R;

/**
 * 프로토타입의 토스트 pill(하단 고정, 1.9초 후 사라짐). 시스템 Toast/Snackbar 대신
 * 화면 안 pill 로 렌더하므로 프로토타입과 위치·모양이 일치한다.
 * 토스트 pill(@id/toastPill)을 가진 레이아웃의 루트를 넘긴다.
 */
public final class ToastPresenter {

    private static final long DURATION_MS = 1900L;

    private ToastPresenter() {}

    public static void show(View anchorRoot, String message) {
        TextView pill = anchorRoot.findViewById(R.id.toastPill);
        if (pill == null) return;

        // 연속 토스트가 겹치면 앞선 숨김 예약이 새 토스트를 조기에 지운다 → 예약을 갈아끼운다.
        Object pending = pill.getTag(R.id.toastPill);
        if (pending instanceof Runnable) {
            pill.removeCallbacks((Runnable) pending);
        }

        pill.setText(message);
        pill.setVisibility(View.VISIBLE);

        Runnable hide = () -> pill.setVisibility(View.GONE);
        pill.setTag(R.id.toastPill, hide);
        pill.postDelayed(hide, DURATION_MS);
    }
}
```

> `setTag(int key, Object)` 의 키는 리소스 ID여야 한다 — `@id/toastPill` 을 키로 재사용한다.

`android/app/src/main/java/com/traveltrace/app/ui/home/TripCardAdapter.java` (신규):

```java
package com.traveltrace.app.ui.home;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ItemTripCardBinding;

import java.util.ArrayList;
import java.util.List;

/** HOME 여행 카드 목록. hero 일러스트는 카드 id 로 고른다(UiState 는 리소스를 모른다). */
public class TripCardAdapter extends RecyclerView.Adapter<TripCardAdapter.VH> {

    public interface Listener {
        void onTripClick(HomeUiState.TripCard card);
    }

    private final List<HomeUiState.TripCard> items = new ArrayList<>();
    private final Listener listener;

    public TripCardAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<HomeUiState.TripCard> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @DrawableRes
    private static int heroFor(String id) {
        return "paris".equals(id) ? R.drawable.hero_trip_paris : R.drawable.hero_trip_jeju;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTripCardBinding b = ItemTripCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        HomeUiState.TripCard card = items.get(position);
        holder.b.tripHero.setImageResource(heroFor(card.id));
        holder.b.tripTitle.setText(card.title);
        holder.b.tripMeta.setText(card.meta);
        holder.b.tripLocation.setText(card.locationLabel);
        holder.b.tripCard.setOnClickListener(v -> listener.onTripClick(card));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTripCardBinding b;

        VH(ItemTripCardBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/home/HomeRenderer.java` (신규):

```java
package com.traveltrace.app.ui.home;

import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.traveltrace.app.databinding.FragmentHomeBinding;

/** HomeUiState → HOME 뷰 반영. 상태 없음 — Robolectric 이 직접 호출해 검증한다. */
public final class HomeRenderer {

    private HomeRenderer() {}

    public static void render(FragmentHomeBinding binding, HomeUiState state,
                              TripCardAdapter.Listener listener) {
        binding.tripList.setVisibility(state.empty ? View.GONE : View.VISIBLE);
        binding.emptyGroup.setVisibility(state.empty ? View.VISIBLE : View.GONE);

        if (state.empty) return;

        TripCardAdapter adapter;
        if (binding.tripList.getAdapter() instanceof TripCardAdapter) {
            adapter = (TripCardAdapter) binding.tripList.getAdapter();
        } else {
            adapter = new TripCardAdapter(listener);
            binding.tripList.setLayoutManager(
                    new LinearLayoutManager(binding.getRoot().getContext()));
            binding.tripList.setAdapter(adapter);
        }
        adapter.submit(state.trips);
    }
}
```

- [ ] **Step 12: HomeViewModel · HomeFragment 작성**

`android/app/src/main/java/com/traveltrace/app/ui/home/HomeViewModel.java` (신규):

```java
package com.traveltrace.app.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 상태를 ScreenFixtures 에서 공급한다.
 * 로직 에픽에서 이 클래스의 공급원만 TripRepository 로 교체된다 — Renderer/레이아웃은 불변.
 */
@HiltViewModel
public class HomeViewModel extends ViewModel {

    private final MutableLiveData<HomeUiState> state = new MutableLiveData<>();

    @Inject
    public HomeViewModel() {
        state.setValue(ScreenFixtures.home());
    }

    public LiveData<HomeUiState> state() {
        return state;
    }

    /** 빈 상태 디자인 확인용 토글 (프로토타입 homeState prop 대응). */
    public void showEmpty(boolean empty) {
        state.setValue(empty ? ScreenFixtures.homeEmpty() : ScreenFixtures.home());
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/home/HomeFragment.java` (신규):

```java
package com.traveltrace.app.ui.home;

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
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import dagger.hilt.android.AndroidEntryPoint;

/** HOME: 여행 목록 / 빈 상태. 렌더는 HomeRenderer, 데이터는 HomeViewModel. */
@AndroidEntryPoint
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        HomeViewModel vm = new ViewModelProvider(this).get(HomeViewModel.class);

        binding.newTripButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_home_to_photo));

        vm.state().observe(getViewLifecycleOwner(), state ->
                HomeRenderer.render(binding, state, this::onTripClick));
    }

    private void onTripClick(HomeUiState.TripCard card) {
        if (card.enabled) {
            NavHostFragment.findNavController(this).navigate(R.id.action_home_to_map);
        } else {
            // 프로토타입 openTripLocked — 제주 카드는 데모에서 열리지 않는다.
            ToastPresenter.show(binding.getRoot(), getString(R.string.home_trip_locked_toast));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
```

- [ ] **Step 13: nav_graph에 HOME 추가 + 시작 지점 변경**

`android/app/src/main/res/navigation/nav_graph.xml` — `app:startDestination` 을 `@id/homeFragment` 로 바꾸고, `photoSelectionFragment` **앞에** HOME 목적지를 추가한다:

```xml
    app:startDestination="@id/homeFragment">

    <fragment
        android:id="@+id/homeFragment"
        android:name="com.traveltrace.app.ui.home.HomeFragment"
        android:label="@string/home_title">
        <action
            android:id="@+id/action_home_to_photo"
            app:destination="@id/photoSelectionFragment" />
        <!-- 프로토타입 openTrip: 저장된 여행은 분석을 건너뛰고 바로 지도로 간다. -->
        <action
            android:id="@+id/action_home_to_map"
            app:destination="@id/mapReplayFragment" />
    </fragment>
```

- [ ] **Step 14: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.home.HomeRendererTest"`
Expected: PASS (5 tests).

- [ ] **Step 15: 앱 빌드 + 육안 대조**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. 기기/에뮬레이터에 설치해 HOME이 프로토타입과 일치하는지 확인 — 여행 카드 2장, 제주 카드 탭 시 토스트, CTA.

- [ ] **Step 16: 커밋**

```bash
git add android/app/src/main/res android/app/src/main/java/com/traveltrace/app/ui android/app/src/test/java/com/traveltrace/app/ui/home
git commit -m "feat: implement HOME screen (trip list, empty state, CTA, toast)"
```

---

### Task 4: SELECT — 기간 카드 + 3열 선택 그리드

프로토타입 `screen:'select'`. 타일 탭 → 선택 토글 → 카운터 갱신까지가 이 화면의 **디자인 상태**이므로 포함한다(실제 갤러리 로딩은 로직 단계).

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Modify: `android/app/src/main/res/values/styles.xml`
- Create: `android/app/src/main/res/drawable/bg_check_badge.xml`
- Create: `android/app/src/main/res/drawable/bg_label_pill.xml`
- Create: `android/app/src/main/res/layout/item_photo_tile.xml`
- Rewrite: `android/app/src/main/res/layout/fragment_photo_selection.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoGridAdapter.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/photo/GridSpacingDecoration.java`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionRenderer.java`
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModel.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionRendererTest.java`

**Interfaces:**
- Consumes: `PhotoSelectionUiState` / `.Tile` / `.selectedCount()` / `.withToggled(int)` / `ScreenFixtures.photoSelection()` (Task 2)
- Produces:
  - `PhotoSelectionRenderer.render(FragmentPhotoSelectionBinding binding, PhotoSelectionUiState state, PhotoGridAdapter.Listener listener)` — static void.
  - `PhotoGridAdapter(PhotoGridAdapter.Listener listener)`; `void submit(List<PhotoSelectionUiState.Tile>)`; `interface Listener { void onTileClick(int index); }`
  - `PhotoSelectionViewModel.state()` → `LiveData<PhotoSelectionUiState>`; `void toggle(int index)`
  - 기존 `fragment_photo_selection.xml` 의 스캐폴딩 뷰 id(`title`, `nextButton`)는 사라진다. `HarnessSmokeTest` 가 이 레이아웃을 인플레이트하지만 루트만 확인하고 두 id 를 참조하지 않으므로 그대로 통과한다.

- [ ] **Step 1: 문자열 추가**

`android/app/src/main/res/values/strings.xml` — HOME 문자열 아래에 추가:

```xml
    <!-- SELECT -->
    <string name="select_title">사진 선택</string>
    <string name="select_back_desc">뒤로</string>
    <string name="select_period_title">여행 기간</string>
    <string name="select_period_change">변경</string>
    <string name="select_start_analyze">분석 시작</string>
    <string name="select_hint">최대 %1$d장 · 탭하여 제외</string>
    <!-- %1$d = 선택 장수. 숫자만 브랜드 색으로 강조하므로 Renderer 에서 Spannable 로 조립한다. -->
    <string name="select_count_prefix">선택한 사진 </string>
    <string name="select_count_suffix">장</string>
    <string name="select_tile_desc">사진 타일</string>
    <string name="select_tile_checked_desc">선택됨</string>
```

이어서 스캐폴딩 문자열 두 개를 **삭제**한다 — 새 레이아웃이 더 이상 참조하지 않는다:

```xml
    <string name="screen_photo_title">1. 사진 선택 (Epic B)</string>
    <string name="action_to_analysis">분석 진행 →</string>
```

`screen_photo_title` 은 `nav_graph.xml` 의 `photoSelectionFragment` 라벨이 참조하므로, 같은 단계에서 라벨을 새 문자열로 바꾼다 (안 하면 리소스 링크 실패):

```xml
    <fragment
        android:id="@+id/photoSelectionFragment"
        android:name="com.traveltrace.app.ui.photo.PhotoSelectionFragment"
        android:label="@string/select_title">
```

> `screen_analysis_title` / `screen_map_title` / `action_to_map` 은 각각 Task 5·7 에서 같은 방식으로 정리한다.

- [ ] **Step 2: 치수·색·스타일 추가**

`android/app/src/main/res/values/dimens.xml` — 이전 태스크의 실측 섹션 아래에 추가:

```xml
    <dimen name="select_icon_tile">42dp</dimen>
    <dimen name="photo_grid_gap">6dp</dimen>
    <dimen name="photo_tile_stroke_selected">3dp</dimen>
    <dimen name="photo_tile_stroke_unselected">1dp</dimen>
    <dimen name="check_badge_size">20dp</dimen>
```

`android/app/src/main/res/values/colors.xml` — 일러스트 섹션 아래에 추가:

```xml
    <!-- SELECT 그리드: 라벨 pill rgba(255,255,255,.7) / 라벨 글자 rgba(25,31,40,.6) -->
    <color name="label_pill_bg">#B3FFFFFF</color>
    <color name="label_pill_text">#99191F28</color>
```

`android/app/src/main/res/values/styles.xml` — `<!-- ============ CARD ============ -->` 섹션의 `Widget.TravelTrace.Card` 아래에 추가:

```xml
    <!-- 사진 그리드 타일: radius 12, 그림자 없음. 톤/링은 어댑터가 코드로 지정. -->
    <style name="Widget.TravelTrace.PhotoTile" parent="Widget.Material3.CardView.Outlined">
        <item name="cardCornerRadius">@dimen/radius_12</item>
        <item name="cardElevation">0dp</item>
    </style>
```

- [ ] **Step 3: 배지·라벨 드로어블 작성**

`android/app/src/main/res/drawable/bg_check_badge.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 선택 체크 배지: 20dp 파란 원. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/fill_brand" />
    <size android:width="@dimen/check_badge_size" android:height="@dimen/check_badge_size" />
</shape>
```

`android/app/src/main/res/drawable/bg_label_pill.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 그리드 타일 라벨 pill: 반투명 흰 배경. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/label_pill_bg" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

- [ ] **Step 4: 그리드 타일 레이아웃 작성**

`android/app/src/main/res/layout/item_photo_tile.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 3열 그리드 타일 1개. 1:1 비율 고정 + 톤 배경 + 선택 링/체크 배지 + 라벨 pill. -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/photoTile"
        style="@style/Widget.TravelTrace.PhotoTile"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:clickable="true"
        android:contentDescription="@string/select_tile_desc"
        android:focusable="true"
        app:layout_constraintDimensionRatio="1:1"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent">

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <TextView
                android:id="@+id/tileLabel"
                style="@style/TextAppearance.TravelTrace.Micro"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom|start"
                android:layout_margin="@dimen/photo_grid_gap"
                android:background="@drawable/bg_label_pill"
                android:paddingStart="6dp"
                android:paddingTop="1dp"
                android:paddingEnd="6dp"
                android:paddingBottom="1dp"
                android:textColor="@color/label_pill_text"
                android:textFontWeight="700"
                android:visibility="gone"
                tools:text="개선문"
                tools:visibility="visible" />

            <ImageView
                android:id="@+id/tileCheck"
                android:layout_width="@dimen/check_badge_size"
                android:layout_height="@dimen/check_badge_size"
                android:layout_gravity="top|end"
                android:layout_margin="@dimen/photo_grid_gap"
                android:background="@drawable/bg_check_badge"
                android:contentDescription="@string/select_tile_checked_desc"
                android:padding="3dp"
                android:src="@drawable/ic_check"
                app:tint="@color/text_on_fill" />
        </FrameLayout>
    </com.google.android.material.card.MaterialCardView>
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 5: SELECT 레이아웃 재작성**

`android/app/src/main/res/layout/fragment_photo_selection.xml` — 기존 스캐폴딩 내용을 전부 지우고 아래로 교체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SELECT: 상단바 + 기간 카드 + 카운터 + 3열 그리드 + CTA. -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_screen"
    android:fitsSystemWindows="true">

    <ImageView
        android:id="@+id/selectBack"
        android:layout_width="@dimen/home_header_icon"
        android:layout_height="@dimen/home_header_icon"
        android:layout_marginStart="12dp"
        android:layout_marginTop="6dp"
        android:contentDescription="@string/select_back_desc"
        android:padding="8dp"
        android:src="@drawable/ic_arrow_back"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:tint="@color/text_primary" />

    <TextView
        android:id="@+id/selectTitle"
        style="@style/TextAppearance.TravelTrace.Title3"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        android:text="@string/select_title"
        android:textSize="@dimen/text_card_title"
        app:layout_constraintBottom_toBottomOf="@id/selectBack"
        app:layout_constraintStart_toEndOf="@id/selectBack"
        app:layout_constraintTop_toTopOf="@id/selectBack" />

    <!-- 여행 기간 카드 -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/periodCard"
        style="@style/Widget.TravelTrace.Card"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="18dp"
        android:layout_marginTop="12dp"
        android:layout_marginEnd="18dp"
        app:cardCornerRadius="@dimen/radius_14"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/selectBack">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingStart="16dp"
            android:paddingTop="14dp"
            android:paddingEnd="16dp"
            android:paddingBottom="14dp">

            <ImageView
                android:layout_width="@dimen/select_icon_tile"
                android:layout_height="@dimen/select_icon_tile"
                android:background="@drawable/bg_pill_glass"
                android:backgroundTint="@color/fill_brand_weak"
                android:contentDescription="@null"
                android:padding="10dp"
                android:src="@drawable/ic_calendar"
                app:tint="@color/fill_brand" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="13dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    style="@style/TextAppearance.TravelTrace.Body"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/select_period_title"
                    android:textFontWeight="700" />

                <TextView
                    android:id="@+id/periodLabel"
                    style="@style/TextAppearance.TravelTrace.Numeric"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="@color/text_tertiary"
                    android:textSize="@dimen/text_caption"
                    tools:text="2024. 6. 12 – 6. 15 · 사진 94장" />
            </LinearLayout>

            <TextView
                android:id="@+id/periodChange"
                style="@style/TextAppearance.TravelTrace.Caption"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/select_period_change"
                android:textColor="@color/text_brand"
                android:textFontWeight="700" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- 카운터 행 -->
    <TextView
        android:id="@+id/selectCount"
        style="@style/TextAppearance.TravelTrace.Label"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="22dp"
        android:layout_marginTop="14dp"
        android:textColor="@color/text_secondary"
        android:textFontWeight="700"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/periodCard"
        tools:text="선택한 사진 16장" />

    <TextView
        android:id="@+id/selectHint"
        style="@style/TextAppearance.TravelTrace.Small"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="22dp"
        app:layout_constraintBottom_toBottomOf="@id/selectCount"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="@id/selectCount"
        tools:text="최대 100장 · 탭하여 제외" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/photoGrid"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="10dp"
        android:clipToPadding="false"
        android:paddingStart="18dp"
        android:paddingEnd="18dp"
        android:paddingBottom="100dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/selectCount"
        tools:itemCount="9"
        tools:listitem="@layout/item_photo_tile"
        tools:spanCount="3" />

    <View
        android:id="@+id/selectCtaFade"
        android:layout_width="0dp"
        android:layout_height="@dimen/cta_fade_height"
        android:background="@drawable/bg_cta_fade"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/startAnalyzeButton"
        style="@style/Widget.TravelTrace.Button.Primary"
        android:layout_width="0dp"
        android:layout_height="@dimen/btn_height_primary"
        android:layout_marginStart="18dp"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="26dp"
        android:text="@string/select_start_analyze"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 6: 실패하는 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/photo/PhotoSelectionRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionRendererTest {

    private FragmentPhotoSelectionBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void rendersAll18TilesInThreeColumns() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertNotNull(binding.photoGrid.getAdapter());
        assertEquals(18, binding.photoGrid.getAdapter().getItemCount());
        assertEquals(3, ((androidx.recyclerview.widget.GridLayoutManager)
                binding.photoGrid.getLayoutManager()).getSpanCount());
    }

    @Test
    public void countReflectsSelectedTiles() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("선택한 사진 16장", binding.selectCount.getText().toString());
    }

    @Test
    public void countUpdatesAfterToggle() {
        PhotoSelectionUiState toggled = ScreenFixtures.photoSelection().withToggled(0);
        PhotoSelectionRenderer.render(binding, toggled, index -> {});

        assertEquals("선택한 사진 15장", binding.selectCount.getText().toString());
    }

    @Test
    public void periodAndHintUsePrototypeCopy() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("2024. 6. 12 – 6. 15 · 사진 94장", binding.periodLabel.getText().toString());
        assertEquals("최대 100장 · 탭하여 제외", binding.selectHint.getText().toString());
    }
}
```

- [ ] **Step 7: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.photo.PhotoSelectionRendererTest"`
Expected: 컴파일 실패 — `cannot find symbol: class PhotoSelectionRenderer`.

- [ ] **Step 8: PhotoGridAdapter 작성**

`android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoGridAdapter.java` (신규):

```java
package com.traveltrace.app.ui.photo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ItemPhotoTileBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 3열 사진 그리드. 실제 썸네일은 로직 단계에서 붙고, 지금은 타일 톤 색으로 대체한다.
 * 선택 링/불투명도는 프로토타입 t.style 을 그대로 옮긴 것:
 * 선택 = 3dp 파란 링 + alpha 1, 해제 = 1dp 옅은 테두리 + alpha .5
 */
public class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.VH> {

    public interface Listener {
        void onTileClick(int index);
    }

    private static final float ALPHA_UNSELECTED = 0.5f;

    private final List<PhotoSelectionUiState.Tile> items = new ArrayList<>();
    private final Listener listener;

    public PhotoGridAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<PhotoSelectionUiState.Tile> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemPhotoTileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PhotoSelectionUiState.Tile tile = items.get(position);
        ItemPhotoTileBinding b = holder.b;
        android.content.Context ctx = b.getRoot().getContext();

        b.photoTile.setCardBackgroundColor(tile.toneColor);
        b.photoTile.setAlpha(tile.selected ? 1f : ALPHA_UNSELECTED);
        b.photoTile.setStrokeColor(ContextCompat.getColor(ctx,
                tile.selected ? R.color.fill_brand : R.color.divider));
        b.photoTile.setStrokeWidth(ctx.getResources().getDimensionPixelSize(tile.selected
                ? R.dimen.photo_tile_stroke_selected
                : R.dimen.photo_tile_stroke_unselected));

        b.tileCheck.setVisibility(tile.selected ? View.VISIBLE : View.GONE);

        if (tile.label == null) {
            b.tileLabel.setVisibility(View.GONE);
        } else {
            b.tileLabel.setVisibility(View.VISIBLE);
            b.tileLabel.setText(tile.label);
        }

        int index = position;
        b.photoTile.setOnClickListener(v -> listener.onTileClick(index));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPhotoTileBinding b;

        VH(ItemPhotoTileBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
```

- [ ] **Step 9: PhotoSelectionRenderer 작성**

`android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionRenderer.java` (신규):

```java
package com.traveltrace.app.ui.photo;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

/** PhotoSelectionUiState → SELECT 뷰 반영. */
public final class PhotoSelectionRenderer {

    private static final int SPAN_COUNT = 3;

    private PhotoSelectionRenderer() {}

    public static void render(FragmentPhotoSelectionBinding binding,
                              PhotoSelectionUiState state,
                              PhotoGridAdapter.Listener listener) {
        Context ctx = binding.getRoot().getContext();

        binding.periodLabel.setText(state.periodLabel);
        binding.selectHint.setText(ctx.getString(R.string.select_hint, state.maxCount));
        binding.selectCount.setText(buildCountText(ctx, state.selectedCount()));

        PhotoGridAdapter adapter;
        if (binding.photoGrid.getAdapter() instanceof PhotoGridAdapter) {
            adapter = (PhotoGridAdapter) binding.photoGrid.getAdapter();
        } else {
            adapter = new PhotoGridAdapter(listener);
            binding.photoGrid.setLayoutManager(new GridLayoutManager(ctx, SPAN_COUNT));
            binding.photoGrid.addItemDecoration(
                    new GridSpacingDecoration(SPAN_COUNT,
                            ctx.getResources().getDimensionPixelSize(R.dimen.photo_grid_gap)));
            binding.photoGrid.setAdapter(adapter);
        }
        adapter.submit(state.tiles);
    }

    /** "선택한 사진 <N>장" — 숫자만 브랜드 색 (프로토타입 span). */
    private static CharSequence buildCountText(Context ctx, int count) {
        String prefix = ctx.getString(R.string.select_count_prefix);
        String number = String.valueOf(count);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(prefix).append(number).append(ctx.getString(R.string.select_count_suffix));
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_brand)),
                prefix.length(), prefix.length() + number.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/photo/GridSpacingDecoration.java` (신규):

```java
package com.traveltrace.app.ui.photo;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** 3열 그리드의 6dp 균등 간격 (프로토타입 grid gap:6px). */
public class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spanCount;
    private final int spacing;

    public GridSpacingDecoration(int spanCount, int spacing) {
        this.spanCount = spanCount;
        this.spacing = spacing;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;
        int column = position % spanCount;

        outRect.left = spacing * column / spanCount;
        outRect.right = spacing - (spacing * (column + 1) / spanCount);
        if (position >= spanCount) {
            outRect.top = spacing;
        }
    }
}
```

- [ ] **Step 10: ViewModel · Fragment 재작성**

`android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionViewModel.java` — 전체 교체:

```java
package com.traveltrace.app.ui.photo;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/** 화면-우선 단계: ScreenFixtures 공급. 로직 에픽에서 MediaStore 조회로 교체된다. */
@HiltViewModel
public class PhotoSelectionViewModel extends ViewModel {

    private final MutableLiveData<PhotoSelectionUiState> state = new MutableLiveData<>();

    @Inject
    public PhotoSelectionViewModel() {
        state.setValue(ScreenFixtures.photoSelection());
    }

    public LiveData<PhotoSelectionUiState> state() {
        return state;
    }

    /** 타일 선택 토글 — 선택 상태는 화면 디자인의 일부다. */
    public void toggle(int index) {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return;
        state.setValue(current.withToggled(index));
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/photo/PhotoSelectionFragment.java` — 전체 교체:

```java
package com.traveltrace.app.ui.photo;

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
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

import dagger.hilt.android.AndroidEntryPoint;

/** SELECT: 기간 카드 + 3열 선택 그리드. */
@AndroidEntryPoint
public class PhotoSelectionFragment extends Fragment {

    private FragmentPhotoSelectionBinding binding;

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
        PhotoSelectionViewModel vm = new ViewModelProvider(this).get(PhotoSelectionViewModel.class);

        binding.selectBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.startAnalyzeButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_photo_to_analysis));

        vm.state().observe(getViewLifecycleOwner(), state ->
                PhotoSelectionRenderer.render(binding, state, vm::toggle));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
```

- [ ] **Step 11: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.photo.PhotoSelectionRendererTest"`
Expected: PASS (4 tests).

- [ ] **Step 12: 앱 빌드 + 육안 대조**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. HOME → "새 여행 만들기" → SELECT. 타일 탭 시 링/체크/카운터가 즉시 반응.

- [ ] **Step 13: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/photo
git commit -m "feat: implement SELECT screen (period card, 3-col photo grid)"
```

---

### Task 5: ANALYZE — 진행 카드 / 완료 전환 / CTA

프로토타입 `screen:'analyze'`의 두 상태(`analyzing` / `done`). 진행률을 **실제로 돌리는 타이머는 만들지 않는다** — 상태는 픽스처가 준다.

> 프로토타입에선 이 화면 뒤에 공용 지도 레이어가 깔린다. 화면 단계에서는 지도 없이 `bg_base` 위에 올린다(계획 상단 인벤토리 주석). 지도 배경 합성은 로직 에픽 12 소관.

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Create: `android/app/src/main/res/drawable/ic_chevron_right.xml`
- Create: `android/app/src/main/res/drawable/bg_progress_card.xml`
- Create: `android/app/src/main/res/drawable/bg_circle_success.xml`
- Create: `android/app/src/main/res/drawable/bg_circle_glass.xml`
- Rewrite: `android/app/src/main/res/layout/fragment_analysis.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisRenderer.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisRendererTest.java`

**Interfaces:**
- Consumes: `AnalysisUiState` / `.progressPercent()` / `ScreenFixtures.analysisInProgress(int)` / `ScreenFixtures.analysisDone()` (Task 2)
- Produces:
  - `AnalysisRenderer.render(FragmentAnalysisBinding binding, AnalysisUiState state)` — static void. analyzing/done 그룹 전환 + 진행률·문구 반영.
  - `AnalysisViewModel.state()` → `LiveData<AnalysisUiState>`; `void setDone(boolean)` (디자인 확인용 토글).

- [ ] **Step 1: 문자열 추가 + 스캐폴딩 정리**

`android/app/src/main/res/values/strings.xml` — SELECT 문자열 아래에 추가:

```xml
    <!-- ANALYZE -->
    <string name="analyze_title">분석</string>
    <string name="analyze_in_progress">사진 분석 중</string>
    <string name="analyze_progress_count">%1$d / %2$d</string>
    <string name="analyze_reading">사진 읽는 중</string>
    <string name="analyze_done">분석 완료</string>
    <string name="analyze_done_summary">경로 %1$d · 위치 미상 %2$d</string>
    <string name="analyze_goto_map">지도에서 여행 보기</string>
```

스캐폴딩 문자열 `screen_analysis_title` 을 **삭제**하고, `nav_graph.xml` 의 `analysisFragment` 라벨을 새 문자열로 교체한다:

```xml
    <fragment
        android:id="@+id/analysisFragment"
        android:name="com.traveltrace.app.ui.analysis.AnalysisFragment"
        android:label="@string/analyze_title">
```

- [ ] **Step 2: 치수·색 추가**

`android/app/src/main/res/values/dimens.xml` — 실측 섹션에 추가:

```xml
    <!-- 진행 카드 radius 16dp — 기존 Radii 목록(11/12/14/18/20/22/24)에 없어 신규 추가 -->
    <dimen name="radius_16">16dp</dimen>
    <dimen name="progress_bar_height">6dp</dimen>
    <dimen name="spinner_size">18dp</dimen>
    <dimen name="spinner_thickness">2.5dp</dimen>
    <dimen name="done_check_size">22dp</dimen>
```

`android/app/src/main/res/values/colors.xml` — 추가:

```xml
    <!-- ANALYZE 진행 카드: rgba(255,255,255,.9) / 뒤로가기 버튼 rgba(255,255,255,.85).
         프로토타입의 backdrop blur 는 post-v1 — 불투명 스크림 폴백. -->
    <color name="glass_card_bg">#E6FFFFFF</color>
    <color name="glass_circle_bg">#D9FFFFFF</color>
    <!-- 진행바 트랙: grey-100 -->
    <color name="progress_track">@color/palette_grey_100</color>
```

- [ ] **Step 3: 드로어블 작성**

`android/app/src/main/res/drawable/ic_chevron_right.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- CTA 우측 화살표 (프로토타입 24x24 path). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M8.47,4.97a0.75,0.75 0,0 1,1.06 0l6,6a0.75,0.75 0,0 1,0 1.06l-6,6a0.75,0.75 0,1 1,-1.06 -1.06L13.94,12 8.47,6.53a0.75,0.75 0,0 1,0 -1.06Z" />
</vector>
```

`android/app/src/main/res/drawable/bg_progress_card.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 진행 카드 배경: radius 16, 반투명 흰색(blur 폴백). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/glass_card_bg" />
    <corners android:radius="@dimen/radius_16" />
</shape>
```

`android/app/src/main/res/drawable/bg_circle_success.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 분석 완료 체크 배경: 22dp 초록 원. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/success_green" />
    <size android:width="@dimen/done_check_size" android:height="@dimen/done_check_size" />
</shape>
```

`android/app/src/main/res/drawable/bg_circle_glass.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 지도/분석 위 플로팅 원형 버튼 배경 (blur 폴백). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/glass_circle_bg" />
</shape>
```

> 진행 카드의 16dp radius 는 Step 2에서 추가한 `radius_16` 을 쓴다.

- [ ] **Step 4: ANALYZE 레이아웃 재작성**

`android/app/src/main/res/layout/fragment_analysis.xml` — 기존 스캐폴딩을 전부 지우고 교체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ANALYZE: 플로팅 뒤로가기 + 진행 카드(진행중/완료) + 완료 CTA. -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_base"
    android:fitsSystemWindows="true">

    <ImageView
        android:id="@+id/analyzeBack"
        android:layout_width="@dimen/home_header_icon"
        android:layout_height="@dimen/home_header_icon"
        android:layout_marginStart="12dp"
        android:layout_marginTop="6dp"
        android:background="@drawable/bg_circle_glass"
        android:contentDescription="@string/select_back_desc"
        android:elevation="@dimen/card_elevation"
        android:padding="9dp"
        android:src="@drawable/ic_arrow_back"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:tint="@color/text_primary" />

    <!-- 진행 카드 -->
    <LinearLayout
        android:id="@+id/progressCard"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:layout_marginTop="8dp"
        android:layout_marginEnd="18dp"
        android:background="@drawable/bg_progress_card"
        android:elevation="2dp"
        android:orientation="vertical"
        android:paddingStart="16dp"
        android:paddingTop="14dp"
        android:paddingEnd="16dp"
        android:paddingBottom="14dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toEndOf="@id/analyzeBack"
        app:layout_constraintTop_toTopOf="parent">

        <!-- 진행중 헤더 -->
        <LinearLayout
            android:id="@+id/analyzingHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <com.google.android.material.progressindicator.CircularProgressIndicator
                android:id="@+id/analyzeSpinner"
                style="@style/Widget.TravelTrace.Spinner"
                android:layout_width="@dimen/spinner_size"
                android:layout_height="@dimen/spinner_size"
                android:indeterminate="true"
                app:indicatorSize="@dimen/spinner_size"
                app:trackThickness="@dimen/spinner_thickness" />

            <TextView
                style="@style/TextAppearance.TravelTrace.Body"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="9dp"
                android:text="@string/analyze_in_progress"
                android:textFontWeight="700" />

            <TextView
                android:id="@+id/analyzeCount"
                style="@style/TextAppearance.TravelTrace.Numeric"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="end"
                android:textColor="@color/text_brand"
                android:textFontWeight="800"
                tools:text="41 / 82" />
        </LinearLayout>

        <com.google.android.material.progressindicator.LinearProgressIndicator
            android:id="@+id/analyzeProgress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="11dp"
            android:max="100"
            app:indicatorColor="@color/fill_brand"
            app:trackColor="@color/progress_track"
            app:trackCornerRadius="3dp"
            app:trackThickness="@dimen/progress_bar_height"
            tools:progress="50" />

        <LinearLayout
            android:id="@+id/recogRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <ImageView
                android:layout_width="14dp"
                android:layout_height="14dp"
                android:contentDescription="@null"
                android:src="@drawable/ic_pin"
                app:tint="@color/fill_brand" />

            <TextView
                android:id="@+id/analyzeRecog"
                style="@style/TextAppearance.TravelTrace.Caption"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="5dp"
                android:textColor="@color/text_secondary"
                android:textFontWeight="600"
                tools:text="루브르 박물관" />
        </LinearLayout>

        <!-- 완료 헤더 -->
        <LinearLayout
            android:id="@+id/doneHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:layout_width="@dimen/done_check_size"
                android:layout_height="@dimen/done_check_size"
                android:background="@drawable/bg_circle_success"
                android:contentDescription="@null"
                android:padding="4dp"
                android:src="@drawable/ic_check"
                app:tint="@color/text_on_fill" />

            <TextView
                style="@style/TextAppearance.TravelTrace.Body"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="9dp"
                android:text="@string/analyze_done"
                android:textFontWeight="700" />

            <TextView
                android:id="@+id/analyzeSummary"
                style="@style/TextAppearance.TravelTrace.Caption"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="end"
                android:textFontWeight="600"
                tools:text="경로 6 · 위치 미상 5" />
        </LinearLayout>
    </LinearLayout>

    <!-- 완료 CTA -->
    <View
        android:id="@+id/analyzeCtaFade"
        android:layout_width="0dp"
        android:layout_height="@dimen/cta_fade_height"
        android:background="@drawable/bg_cta_fade"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/gotoMapButton"
        style="@style/Widget.TravelTrace.Button.Primary"
        android:layout_width="0dp"
        android:layout_height="@dimen/btn_height_primary"
        android:layout_marginStart="18dp"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="28dp"
        android:text="@string/analyze_goto_map"
        android:visibility="gone"
        app:icon="@drawable/ic_chevron_right"
        app:iconGravity="textEnd"
        app:iconPadding="7dp"
        app:iconTint="@color/text_on_fill"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        tools:visibility="visible" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

> 프로토타입 진행 카드는 `left:62px` 로 뒤로가기 버튼 옆에 붙는다. 위 레이아웃은 `analyzeBack`(12dp + 40dp) 오른쪽에 10dp 를 더해 동일한 위치가 된다.

- [ ] **Step 5: 실패하는 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnalysisRendererTest {

    private FragmentAnalysisBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentAnalysisBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void inProgress_showsSpinnerRowAndHidesDoneRowAndCta() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));

        assertEquals(View.VISIBLE, binding.analyzingHeader.getVisibility());
        assertEquals(View.VISIBLE, binding.analyzeProgress.getVisibility());
        assertEquals(View.VISIBLE, binding.recogRow.getVisibility());
        assertEquals(View.GONE, binding.doneHeader.getVisibility());
        assertEquals(View.GONE, binding.gotoMapButton.getVisibility());
    }

    @Test
    public void inProgress_showsCountAndPercent() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));

        assertEquals("41 / 82", binding.analyzeCount.getText().toString());
        assertEquals(50, binding.analyzeProgress.getProgress());
    }

    @Test
    public void inProgress_showsRecognizedNameForThreshold() {
        // lit 임계치: 41 >= 38(센강) 이지만 54(루브르) 미만
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));
        assertEquals("센강 유람선", binding.analyzeRecog.getText().toString());
    }

    @Test
    public void beforeFirstThreshold_showsReadingPlaceholder() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(3));
        assertEquals("사진 읽는 중", binding.analyzeRecog.getText().toString());
    }

    @Test
    public void done_swapsToDoneHeaderAndRevealsCta() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisDone());

        assertEquals(View.GONE, binding.analyzingHeader.getVisibility());
        assertEquals(View.GONE, binding.analyzeProgress.getVisibility());
        assertEquals(View.GONE, binding.recogRow.getVisibility());
        assertEquals(View.VISIBLE, binding.doneHeader.getVisibility());
        assertEquals(View.VISIBLE, binding.gotoMapButton.getVisibility());
        assertEquals("경로 6 · 위치 미상 5", binding.analyzeSummary.getText().toString());
    }
}
```

- [ ] **Step 6: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.analysis.AnalysisRendererTest"`
Expected: 컴파일 실패 — `cannot find symbol: class AnalysisRenderer`.

- [ ] **Step 7: AnalysisRenderer 작성**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisRenderer.java` (신규):

```java
package com.traveltrace.app.ui.analysis;

import android.content.Context;
import android.view.View;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;

/** AnalysisUiState → ANALYZE 뷰 반영. 진행중/완료 두 형태를 한 카드 안에서 갈아끼운다. */
public final class AnalysisRenderer {

    private AnalysisRenderer() {}

    public static void render(FragmentAnalysisBinding binding, AnalysisUiState state) {
        Context ctx = binding.getRoot().getContext();
        int analyzingVis = state.done ? View.GONE : View.VISIBLE;
        int doneVis = state.done ? View.VISIBLE : View.GONE;

        binding.analyzingHeader.setVisibility(analyzingVis);
        binding.analyzeProgress.setVisibility(analyzingVis);
        binding.recogRow.setVisibility(analyzingVis);

        binding.doneHeader.setVisibility(doneVis);
        binding.gotoMapButton.setVisibility(doneVis);
        binding.analyzeCtaFade.setVisibility(doneVis);

        if (state.done) {
            binding.analyzeSummary.setText(ctx.getString(
                    R.string.analyze_done_summary, state.routeCount, state.unknownCount));
            return;
        }

        binding.analyzeCount.setText(ctx.getString(
                R.string.analyze_progress_count, state.analyzed, state.total));
        binding.analyzeProgress.setProgress(state.progressPercent());
        binding.analyzeRecog.setText(state.recognizedName);
    }
}
```

- [ ] **Step 8: ViewModel · Fragment 재작성**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java` — 전체 교체:

```java
package com.traveltrace.app.ui.analysis;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 정지된 진행 상태를 공급한다. 진행률을 실제로 올리는 타이머·파이프라인은
 * 로직 에픽(10·11) 소관 — 여기서 만들지 않는다.
 */
@HiltViewModel
public class AnalysisViewModel extends ViewModel {

    /** 디자인 확인용 대표 진행값 (프로토타입 중간 지점). */
    private static final int PREVIEW_ANALYZED = 41;

    private final MutableLiveData<AnalysisUiState> state = new MutableLiveData<>();

    @Inject
    public AnalysisViewModel() {
        state.setValue(ScreenFixtures.analysisInProgress(PREVIEW_ANALYZED));
    }

    public LiveData<AnalysisUiState> state() {
        return state;
    }

    /** 완료형 디자인 확인용 토글. */
    public void setDone(boolean done) {
        state.setValue(done
                ? ScreenFixtures.analysisDone()
                : ScreenFixtures.analysisInProgress(PREVIEW_ANALYZED));
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisFragment.java` — 전체 교체:

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

import dagger.hilt.android.AndroidEntryPoint;

/** ANALYZE: 진행 카드 + 완료 전환. */
@AndroidEntryPoint
public class AnalysisFragment extends Fragment {

    private FragmentAnalysisBinding binding;

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
        AnalysisViewModel vm = new ViewModelProvider(this).get(AnalysisViewModel.class);

        binding.analyzeBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.gotoMapButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_analysis_to_map));

        vm.state().observe(getViewLifecycleOwner(), state ->
                AnalysisRenderer.render(binding, state));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
```

- [ ] **Step 9: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.analysis.AnalysisRendererTest"`
Expected: PASS (5 tests).

- [ ] **Step 10: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/analysis
git commit -m "feat: implement ANALYZE screen (progress card, done state, CTA)"
```

---

### Task 6: ANALYZE — 타임존 확인 시트

프로토타입 `sheet:'tz'`. 분석 시작 직후 뜨는 모달 바텀시트. 테마에 이미 `bottomSheetDialogTheme`(`ThemeOverlay.TravelTrace.BottomSheet`)이 걸려 있으므로 모서리·배경은 자동 상속된다.

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Create: `android/app/src/main/res/layout/sheet_timezone.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/analysis/TimezoneSheetFragment.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/analysis/TimezoneSheetTest.java`

**Interfaces:**
- Consumes: 앱 테마의 `bottomSheetDialogTheme` (기존 `styles.xml`)
- Produces:
  - `TimezoneSheetFragment.newInstance(String cityName)` → `TimezoneSheetFragment`
  - `TimezoneSheetFragment.TAG` (String, `"tz_sheet"`)
  - `TimezoneSheetFragment.Listener { void onTimezoneConfirmed(); void onChangeCityRequested(); }` — 호스트 Fragment 가 구현한다.
  - `TimezoneSheetFragment.bindContent(SheetTimezoneBinding binding, String cityName)` — static, 테스트가 다이얼로그 없이 콘텐츠 렌더를 검증할 수 있게 분리.

- [ ] **Step 1: 문자열·치수 추가**

`android/app/src/main/res/values/strings.xml` — ANALYZE 문자열 아래에 추가:

```xml
    <!-- ANALYZE · 타임존 시트 -->
    <string name="tz_title_prefix">이 여행, </string>
    <string name="tz_title_suffix"> 기준이 맞죠?</string>
    <string name="tz_body">사진 시각을 %1$s 시간대로 맞춰 정확한 순서로 정렬해요. 다른 도시라면 바꿔 주세요.</string>
    <string name="tz_confirm">네, %1$s 기준이 맞아요</string>
    <string name="tz_change_city">다른 도시 선택</string>
```

`android/app/src/main/res/values/dimens.xml` — 실측 섹션에 추가:

```xml
    <!-- 시트 내 버튼: 52dp / radius 13 (프로토타입 실측, CTA 54/14 와 다름) -->
    <dimen name="btn_height_sheet">52dp</dimen>
    <dimen name="radius_13">13dp</dimen>
```

- [ ] **Step 2: 시트 레이아웃 작성**

`android/app/src/main/res/layout/sheet_timezone.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 타임존 확인 시트: 핸들 + 제목(도시명 강조) + 본문 + 확인/변경 버튼. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/surface"
    android:orientation="vertical"
    android:paddingStart="22dp"
    android:paddingTop="24dp"
    android:paddingEnd="22dp"
    android:paddingBottom="30dp">

    <View
        android:layout_width="@dimen/sheet_handle_width"
        android:layout_height="@dimen/sheet_handle_height"
        android:layout_gravity="center_horizontal"
        android:layout_marginBottom="18dp"
        android:background="@drawable/handle_bottom_sheet" />

    <TextView
        android:id="@+id/tzTitle"
        style="@style/TextAppearance.TravelTrace.Title2"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        tools:text="이 여행, 파리 기준이 맞죠?" />

    <TextView
        android:id="@+id/tzBody"
        style="@style/TextAppearance.TravelTrace.Label"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:lineHeight="@dimen/line_body"
        android:textColor="@color/text_secondary"
        android:textFontWeight="500"
        tools:text="사진 시각을 파리 시간대로 맞춰 정확한 순서로 정렬해요. 다른 도시라면 바꿔 주세요." />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/tzConfirm"
        style="@style/Widget.TravelTrace.Button.Primary"
        android:layout_width="match_parent"
        android:layout_height="@dimen/btn_height_sheet"
        android:layout_marginTop="22dp"
        app:cornerRadius="@dimen/radius_13"
        tools:text="네, 파리 기준이 맞아요" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/tzChangeCity"
        style="@style/Widget.TravelTrace.Button.Neutral"
        android:layout_width="match_parent"
        android:layout_height="@dimen/btn_height_sheet"
        android:layout_marginTop="9dp"
        android:text="@string/tz_change_city"
        app:cornerRadius="@dimen/radius_13" />
</LinearLayout>
```

- [ ] **Step 3: 실패하는 시트 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/TimezoneSheetTest.java` (신규):

```java
package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetTimezoneBinding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TimezoneSheetTest {

    private SheetTimezoneBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = SheetTimezoneBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void interpolatesCityIntoTitleBodyAndConfirmButton() {
        TimezoneSheetFragment.bindContent(binding, "파리");

        assertEquals("이 여행, 파리 기준이 맞죠?", binding.tzTitle.getText().toString());
        assertEquals("네, 파리 기준이 맞아요", binding.tzConfirm.getText().toString());
        assertEquals("사진 시각을 파리 시간대로 맞춰 정확한 순서로 정렬해요. 다른 도시라면 바꿔 주세요.",
                binding.tzBody.getText().toString());
    }

    @Test
    public void changeCityButtonUsesFixedCopy() {
        TimezoneSheetFragment.bindContent(binding, "파리");
        assertEquals("다른 도시 선택", binding.tzChangeCity.getText().toString());
    }
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.analysis.TimezoneSheetTest"`
Expected: 컴파일 실패 — `cannot find symbol: class TimezoneSheetFragment`.

- [ ] **Step 5: TimezoneSheetFragment 작성**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/TimezoneSheetFragment.java` (신규):

```java
package com.traveltrace.app.ui.analysis;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetTimezoneBinding;

/**
 * 타임존 확인 시트 (프로토타입 sheet:'tz'). 도시명은 제목·본문·확인 버튼 세 곳에 끼어들어가고,
 * 제목에서만 브랜드 색으로 강조된다.
 */
public class TimezoneSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "tz_sheet";
    private static final String ARG_CITY = "city";

    /** 호스트 Fragment 가 구현한다. */
    public interface Listener {
        void onTimezoneConfirmed();

        void onChangeCityRequested();
    }

    private SheetTimezoneBinding binding;
    private Listener listener;

    public static TimezoneSheetFragment newInstance(String cityName) {
        TimezoneSheetFragment f = new TimezoneSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CITY, cityName);
        f.setArguments(args);
        return f;
    }

    /** 콘텐츠 렌더만 분리 — 다이얼로그 없이도 테스트할 수 있다. */
    public static void bindContent(SheetTimezoneBinding binding, String cityName) {
        Context ctx = binding.getRoot().getContext();

        String prefix = ctx.getString(R.string.tz_title_prefix);
        SpannableStringBuilder title = new SpannableStringBuilder();
        title.append(prefix).append(cityName).append(ctx.getString(R.string.tz_title_suffix));
        title.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_brand)),
                prefix.length(), prefix.length() + cityName.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        binding.tzTitle.setText(title);
        binding.tzBody.setText(ctx.getString(R.string.tz_body, cityName));
        binding.tzConfirm.setText(ctx.getString(R.string.tz_confirm, cityName));
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof Listener) {
            listener = (Listener) getParentFragment();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetTimezoneBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String city = requireArguments().getString(ARG_CITY, "");
        bindContent(binding, city);

        binding.tzConfirm.setOnClickListener(v -> {
            if (listener != null) listener.onTimezoneConfirmed();
            dismiss();
        });
        binding.tzChangeCity.setOnClickListener(v -> {
            if (listener != null) listener.onChangeCityRequested();
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
```

- [ ] **Step 6: AnalysisFragment 에서 시트 띄우기**

`android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisFragment.java` — 클래스 선언에 `implements TimezoneSheetFragment.Listener` 를 추가하고, 아래 변경을 적용한다.

클래스 선언 교체:

```java
public class AnalysisFragment extends Fragment implements TimezoneSheetFragment.Listener {
```

`onViewCreated` 의 `vm.state().observe(...)` 호출 **아래**에 시트 표시를 추가:

```java
        // 프로토타입 startAnalyze: ANALYZE 진입 직후 타임존 확인 시트가 뜬다.
        // 회전 등으로 재생성될 때 두 번 띄우지 않도록 savedInstanceState 로 가드한다.
        if (savedInstanceState == null
                && getChildFragmentManager().findFragmentByTag(TimezoneSheetFragment.TAG) == null) {
            TimezoneSheetFragment.newInstance(ScreenFixtures.cityLabel())
                    .show(getChildFragmentManager(), TimezoneSheetFragment.TAG);
        }
```

클래스 끝(`onDestroyView` 앞)에 리스너 구현 추가:

```java
    @Override
    public void onTimezoneConfirmed() {
        // 화면 단계에선 확인만 받고 끝 — 실제 분석 파이프라인 착수는 로직 에픽 10·11 소관.
    }

    @Override
    public void onChangeCityRequested() {
        ToastPresenter.show(binding.getRoot(), getString(R.string.tz_change_city_toast));
    }
```

import 추가:

```java
import com.traveltrace.app.ui.common.ToastPresenter;
import com.traveltrace.app.ui.preview.ScreenFixtures;
```

> `newInstance` 는 **child** FragmentManager 로 띄운다 — `onAttach` 의 `getParentFragment() instanceof Listener` 가 성립해야 리스너가 붙는다.

- [ ] **Step 7: 토스트 문자열·pill 추가**

`android/app/src/main/res/values/strings.xml` 에 추가:

```xml
    <string name="tz_change_city_toast">도시 선택은 데모에서 생략돼요</string>
```

`ToastPresenter` 는 `@id/toastPill` 을 가진 루트를 요구한다. `fragment_analysis.xml` 의 루트 `ConstraintLayout` 닫는 태그 **앞**에 토스트 pill 을 추가한다 (HOME 과 동일한 뷰):

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

- [ ] **Step 8: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.analysis.*"`
Expected: PASS (AnalysisRendererTest 5 + TimezoneSheetTest 2 = 7 tests).

- [ ] **Step 9: 앱 빌드 + 육안 대조**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. SELECT → "분석 시작" → ANALYZE 진입과 동시에 타임존 시트. "다른 도시 선택" → 토스트.

- [ ] **Step 10: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/analysis
git commit -m "feat: add timezone confirm sheet to ANALYZE"
```

---

### Task 7: MAP — 지도 셸 + 상단바 + 위치 미상 칩

프로토타입 `screen:'map'`의 바탕. 실제 Google 지도를 **정적 카메라**(파리 고정)로 띄우고 그 위 크롬만 구현한다. 핀·경로·리플레이 카메라 이동은 로직 에픽 12~14 소관 — 여기서 만들지 않는다.

> **레이아웃 분할 원칙(이 태스크가 확립):** `fragment_map_replay.xml` 은 `SupportMapFragment` 를 담는 `FragmentContainerView` 를 갖는다. 이 뷰는 FragmentManager 밖에서 인플레이트하면 예외를 던지므로 **Robolectric 이 통째로 인플레이트할 수 없다.** 따라서 크롬은 전부 `<include>` 되는 독립 레이아웃(`view_map_top_bar` 등)으로 분리하고, Renderer 테스트는 그 include 레이아웃만 단독 인플레이트한다.

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Create: `android/app/src/main/res/drawable/bg_pill_segment.xml`
- Create: `android/app/src/main/res/drawable/bg_segment_selected.xml`
- Create: `android/app/src/main/res/drawable/bg_chip_unknown.xml`
- Create: `android/app/src/main/res/drawable/bg_scrim_satellite.xml`
- Create: `android/app/src/main/res/layout/view_map_top_bar.xml`
- Rewrite: `android/app/src/main/res/layout/fragment_map_replay.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Rewrite: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapTopBarRendererTest.java`

**Interfaces:**
- Consumes: `MapUiState` / `.Stop` / `.Speed` / `ScreenFixtures.map()` (Task 2), Maps SDK 키(`AndroidManifest.xml` 의 `com.google.android.geo.API_KEY`, `local.properties` 에 설정 완료)
- Produces:
  - `MapRenderer.renderTopBar(ViewMapTopBarBinding binding, MapUiState state)` — static void. 제목·지도/위성 세그먼트 선택 상태·위치 미상 칩.
  - `MapReplayViewModel.state()` → `LiveData<MapUiState>`; `void setSatellite(boolean)`
  - `MapReplayFragment` 가 `OnMapReadyCallback` 을 구현하고 파리 좌표에 카메라를 고정한다.
  - 상수 `MapReplayFragment.PARIS` (`LatLng`), `MapReplayFragment.STATIC_ZOOM` (float, 12f)
  - Task 9·10·11 이 `fragment_map_replay.xml` 의 include 지점(`mapBottomSheet`, `cinemaOverlay`)에 각자 레이아웃을 붙인다.

- [ ] **Step 1: 문자열 추가 + 스캐폴딩 정리**

`android/app/src/main/res/values/strings.xml` — 추가:

```xml
    <!-- MAP -->
    <string name="map_title">지도</string>
    <string name="map_tab_map">지도</string>
    <string name="map_tab_satellite">위성</string>
    <string name="map_unknown_chip">위치 미상 %1$d</string>
```

스캐폴딩 문자열 `screen_map_title` / `action_to_map` 을 **삭제**하고, `nav_graph.xml` 의 `mapReplayFragment` 라벨을 교체한다:

```xml
    <fragment
        android:id="@+id/mapReplayFragment"
        android:name="com.traveltrace.app.ui.map.MapReplayFragment"
        android:label="@string/map_title" />
```

- [ ] **Step 2: 치수·색 추가**

`android/app/src/main/res/values/dimens.xml` — 추가:

```xml
    <dimen name="map_bar_height">40dp</dimen>
    <dimen name="map_segment_height">34dp</dimen>
    <dimen name="map_chip_height">32dp</dimen>
```

`android/app/src/main/res/values/colors.xml` — 추가:

```xml
    <!-- MAP 상단바 pill: rgba(255,255,255,.92) (blur 폴백) -->
    <color name="glass_bar_bg">#EBFFFFFF</color>
    <!-- 위성 뷰 스크림: 상단 밝게 → 하단 어둡게 (프로토타입 linear-gradient) -->
    <color name="satellite_scrim_top">#1A7896AA</color>
    <color name="satellite_scrim_bottom">#38000000</color>
```

- [ ] **Step 3: 드로어블 작성**

`android/app/src/main/res/drawable/bg_pill_segment.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 지도/위성 세그먼트 컨테이너 + 제목 pill 공용 배경 (blur 폴백). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/glass_bar_bg" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

`android/app/src/main/res/drawable/bg_chip_unknown.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- "위치 미상 N" 칩: rgba(25,31,40,.82) 다크 pill. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/badge_unknown_fill" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

`android/app/src/main/res/drawable/bg_scrim_satellite.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 위성 뷰 위 스크림 (프로토타입 linear-gradient 180deg). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="270"
        android:startColor="@color/satellite_scrim_top"
        android:endColor="@color/satellite_scrim_bottom" />
</shape>
```

- [ ] **Step 4: 상단바 레이아웃 작성 (단독 인플레이트 가능)**

`android/app/src/main/res/layout/view_map_top_bar.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- MAP 상단바: 뒤로 + 제목 pill + 지도/위성 세그먼트 + 위치 미상 칩.
     지도 레이아웃과 분리해 Robolectric 이 단독 인플레이트할 수 있게 한다. -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/topBarRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingStart="14dp"
    android:paddingTop="8dp"
    android:paddingEnd="14dp">

    <ImageView
        android:id="@+id/mapBack"
        android:layout_width="@dimen/map_bar_height"
        android:layout_height="@dimen/map_bar_height"
        android:background="@drawable/bg_circle_glass"
        android:contentDescription="@string/select_back_desc"
        android:elevation="@dimen/card_elevation"
        android:padding="9dp"
        android:src="@drawable/ic_arrow_back"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:tint="@color/text_primary" />

    <TextView
        android:id="@+id/mapTitle"
        style="@style/TextAppearance.TravelTrace.Body"
        android:layout_width="0dp"
        android:layout_height="@dimen/map_bar_height"
        android:layout_marginStart="10dp"
        android:layout_marginEnd="10dp"
        android:background="@drawable/bg_pill_segment"
        android:elevation="@dimen/card_elevation"
        android:gravity="center_vertical"
        android:letterSpacing="-0.02"
        android:paddingStart="15dp"
        android:paddingEnd="15dp"
        android:singleLine="true"
        android:textFontWeight="800"
        app:layout_constraintEnd_toStartOf="@id/viewSegment"
        app:layout_constraintStart_toEndOf="@id/mapBack"
        app:layout_constraintTop_toTopOf="@id/mapBack"
        tools:text="2024 파리 여행" />

    <LinearLayout
        android:id="@+id/viewSegment"
        android:layout_width="wrap_content"
        android:layout_height="@dimen/map_bar_height"
        android:background="@drawable/bg_pill_segment"
        android:elevation="@dimen/card_elevation"
        android:orientation="horizontal"
        android:padding="3dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="@id/mapBack">

        <TextView
            android:id="@+id/tabMap"
            style="@style/TextAppearance.TravelTrace.Caption"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/map_segment_height"
            android:gravity="center"
            android:paddingStart="13dp"
            android:paddingEnd="13dp"
            android:text="@string/map_tab_map"
            android:textFontWeight="700" />

        <TextView
            android:id="@+id/tabSatellite"
            style="@style/TextAppearance.TravelTrace.Caption"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/map_segment_height"
            android:gravity="center"
            android:paddingStart="13dp"
            android:paddingEnd="13dp"
            android:text="@string/map_tab_satellite"
            android:textFontWeight="700" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/unknownChip"
        android:layout_width="wrap_content"
        android:layout_height="@dimen/map_chip_height"
        android:layout_marginTop="10dp"
        android:background="@drawable/bg_chip_unknown"
        android:elevation="@dimen/card_elevation"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingEnd="12dp"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/mapBack">

        <ImageView
            android:layout_width="14dp"
            android:layout_height="14dp"
            android:contentDescription="@null"
            android:src="@drawable/ic_info"
            app:tint="@color/unknown_yellow" />

        <TextView
            android:id="@+id/unknownChipText"
            style="@style/TextAppearance.TravelTrace.Small"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="6dp"
            android:textColor="@color/text_on_fill"
            android:textFontWeight="700"
            tools:text="위치 미상 5" />
    </LinearLayout>
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 5: MAP 레이아웃 재작성**

`android/app/src/main/res/layout/fragment_map_replay.xml` — 전체 교체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  MAP: 실제 지도 + 위성 스크림 + 상단바. 하단시트/상영모드/드로어는 Task 9·10·11 이
  아래 include 지점에 붙인다.
-->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/mapRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/mapContainer"
        android:name="com.google.android.gms.maps.SupportMapFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <View
        android:id="@+id/satelliteScrim"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="@drawable/bg_scrim_satellite"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <include
        android:id="@+id/mapTopBar"
        layout="@layout/view_map_top_bar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

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
</androidx.constraintlayout.widget.ConstraintLayout>
```

> 상단바는 시스템 상태바와 겹치지 않도록 `MapReplayFragment` 가 인셋을 적용한다(Step 8). 지도는 화면 전체를 덮어야 하므로 루트에 `fitsSystemWindows` 를 쓰지 않는다.

- [ ] **Step 6: 실패하는 상단바 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapTopBarRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapTopBarBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapTopBarRendererTest {

    private Context ctx;
    private ViewMapTopBarBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewMapTopBarBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void showsTripTitleAndUnknownCount() {
        MapRenderer.renderTopBar(binding, ScreenFixtures.map());

        assertEquals("2024 파리 여행", binding.mapTitle.getText().toString());
        assertEquals("위치 미상 5", binding.unknownChipText.getText().toString());
    }

    @Test
    public void mapTabIsSelectedByDefault() {
        MapRenderer.renderTopBar(binding, ScreenFixtures.map());

        assertEquals(ctx.getColor(R.color.text_on_fill), binding.tabMap.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary),
                binding.tabSatellite.getCurrentTextColor());
    }

    @Test
    public void satelliteTabSelectionSwapsHighlight() {
        MapUiState base = ScreenFixtures.map();
        MapUiState sat = new MapUiState(base.tripTitle, base.unknownCount, base.stops,
                base.activeIndex, base.playing, true, base.cinema, base.speed);

        MapRenderer.renderTopBar(binding, sat);

        assertEquals(ctx.getColor(R.color.text_on_fill),
                binding.tabSatellite.getCurrentTextColor());
        assertNotEquals(ctx.getColor(R.color.text_on_fill), binding.tabMap.getCurrentTextColor());
    }
}
```

- [ ] **Step 7: MapRenderer 작성**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java` (신규):

```java
package com.traveltrace.app.ui.map;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapTopBarBinding;

/**
 * MapUiState → MAP 크롬 반영. 지도 레이아웃(FragmentContainerView)과 분리된 include
 * 레이아웃만 다루므로 Robolectric 이 단독 검증할 수 있다.
 * Task 9·10 이 renderSheet / renderCinema 를 이 클래스에 추가한다.
 */
public final class MapRenderer {

    private MapRenderer() {}

    public static void renderTopBar(ViewMapTopBarBinding binding, MapUiState state) {
        Context ctx = binding.getRoot().getContext();

        binding.mapTitle.setText(state.tripTitle);
        binding.unknownChipText.setText(
                ctx.getString(R.string.map_unknown_chip, state.unknownCount));
        binding.unknownChip.setVisibility(state.unknownCount > 0 ? View.VISIBLE : View.GONE);

        applyTab(ctx, binding.tabMap, !state.satellite);
        applyTab(ctx, binding.tabSatellite, state.satellite);
    }

    /** 선택 탭 = 파란 pill + 흰 글자 / 비선택 = 투명 + tertiary 글자 (프로토타입 mapTabBg/Fg). */
    private static void applyTab(Context ctx, TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_segment_selected : 0);
        tab.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.text_on_fill : R.color.text_tertiary));
    }
}
```

`android/app/src/main/res/drawable/bg_segment_selected.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 선택된 세그먼트 탭: 파란 pill. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/fill_brand" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

- [ ] **Step 8: ViewModel · Fragment 재작성**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java` — 전체 교체:

```java
package com.traveltrace.app.ui.map;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 정지된 지도 상태를 공급한다. 리플레이 진행·카메라 이동은
 * 로직 에픽 12~14 소관 — 여기서 만들지 않는다.
 */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();

    @Inject
    public MapReplayViewModel() {
        state.setValue(ScreenFixtures.map());
    }

    public LiveData<MapUiState> state() {
        return state;
    }

    public void setSatellite(boolean satellite) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(new MapUiState(s.tripTitle, s.unknownCount, s.stops, s.activeIndex,
                s.playing, satellite, s.cinema, s.speed));
    }
}
```

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java` — 전체 교체:

```java
package com.traveltrace.app.ui.map;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.traveltrace.app.databinding.FragmentMapReplayBinding;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * MAP: 정적 카메라의 실제 지도 + 크롬. 핀/경로/리플레이는 로직 에픽 소관이라
 * 이 단계에선 지도를 파리에 고정만 한다.
 */
@AndroidEntryPoint
public class MapReplayFragment extends Fragment implements OnMapReadyCallback {

    /** 프로토타입 데모 여행지 — 로직 단계에선 여행의 실제 bounds 로 대체된다. */
    public static final LatLng PARIS = new LatLng(48.8566, 2.3522);
    public static final float STATIC_ZOOM = 12f;

    private FragmentMapReplayBinding binding;
    private MapReplayViewModel vm;
    private GoogleMap map;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMapReplayBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(MapReplayViewModel.class);

        // 지도는 전체 화면을 덮으므로 상단바에만 상태바 인셋을 준다.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapTopBar.topBarRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(
                        binding.mapContainer.getId());
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        binding.mapTopBar.mapBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.mapTopBar.tabMap.setOnClickListener(v -> vm.setSatellite(false));
        binding.mapTopBar.tabSatellite.setOnClickListener(v -> vm.setSatellite(true));

        vm.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MapUiState state) {
        MapRenderer.renderTopBar(binding.mapTopBar, state);
        binding.satelliteScrim.setVisibility(state.satellite ? View.VISIBLE : View.GONE);
        if (map != null) {
            map.setMapType(state.satellite ? GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setMapToolbarEnabled(false);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));

        MapUiState state = vm.state().getValue();
        if (state != null) render(state);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        map = null;
        binding = null;
    }
}
```

- [ ] **Step 9: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.MapTopBarRendererTest"`
Expected: PASS (3 tests).

- [ ] **Step 10: 앱 빌드 + 육안 대조 (실기기/에뮬레이터 필요)**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. HOME → 파리 카드 → MAP. 파리 지도가 뜨고 상단바·칩이 겹쳐 보이며, 지도/위성 탭이 실제로 지도 타입을 바꾼다.

> 지도가 **회색으로만** 보이면 `local.properties` 의 `MAPS_API_KEY` 가 유효하지 않거나 해당 키에 "Maps SDK for Android" 가 활성화되지 않은 것이다. 크롬(상단바·칩)은 그래도 정상 렌더되므로 이 태스크의 디자인 검증은 계속할 수 있다.

- [ ] **Step 11: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: implement MAP shell (static map, top bar, unknown chip)"
```

---

### Task 8: MAP — 타임라인 스크러버 커스텀 뷰

프로토타입 하단 시트의 스크러버: 도트 6개 + 연결선. 지나온 구간은 파랑, 남은 구간은 회색, 활성 도트는 확대, AI 정차점은 점선 테두리. 표준 위젯으로 재현할 수 없어 커스텀 `View` 로 만든다.

**Files:**
- Modify: `android/app/src/main/res/values/dimens.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/TimelineScrubberView.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/TimelineScrubberViewTest.java`

**Interfaces:**
- Consumes: `MapUiState.Stop` (Task 2) — `ai` 플래그만 쓴다
- Produces:
  - `TimelineScrubberView(Context)` / `(Context, AttributeSet)` — 레이아웃에서 인플레이트 가능
  - `void setStops(List<MapUiState.Stop> stops)`
  - `void setActiveIndex(int index)` / `int getActiveIndex()`
  - `void setOnStopSelectedListener(OnStopSelectedListener l)`; `interface OnStopSelectedListener { void onStopSelected(int index); }`
  - `int indexAt(float x)` — x 좌표 → 정차점 인덱스 (테스트가 탭 판정을 직접 검증한다). 정차점이 없으면 `-1`.

- [ ] **Step 1: 치수 추가**

`android/app/src/main/res/values/dimens.xml` — 추가:

```xml
    <!-- 타임라인 스크러버 (프로토타입 실측: 선 3dp, 도트 11dp, 활성 15dp) -->
    <dimen name="scrubber_line_height">3dp</dimen>
    <dimen name="scrubber_dot">11dp</dimen>
    <dimen name="scrubber_dot_active">15dp</dimen>
    <dimen name="scrubber_dot_stroke">2dp</dimen>
    <dimen name="scrubber_height">24dp</dimen>
```

- [ ] **Step 2: 실패하는 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/map/TimelineScrubberViewTest.java` (신규):

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TimelineScrubberViewTest {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 24;

    private TimelineScrubberView view;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        view = new TimelineScrubberView(ctx);
        view.setStops(ScreenFixtures.map().stops);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
    }

    @Test
    public void activeIndexDefaultsToZero() {
        assertEquals(0, view.getActiveIndex());
    }

    @Test
    public void setActiveIndexIsClampedToStopRange() {
        view.setActiveIndex(99);
        assertEquals(5, view.getActiveIndex());

        view.setActiveIndex(-3);
        assertEquals(0, view.getActiveIndex());
    }

    @Test
    public void indexAtMapsLeftEdgeToFirstStopAndRightEdgeToLast() {
        assertEquals(0, view.indexAt(0f));
        assertEquals(5, view.indexAt(WIDTH));
    }

    @Test
    public void indexAtMapsMiddleToMiddleStop() {
        // 6개 정차점을 6등분 → 중앙(180px)은 index 3 슬롯의 시작
        assertEquals(3, view.indexAt(WIDTH / 2f));
    }

    @Test
    public void indexAtReturnsNoSelectionWhenEmpty() {
        TimelineScrubberView empty =
                new TimelineScrubberView(ApplicationProvider.getApplicationContext());
        assertEquals(-1, empty.indexAt(10f));
    }

    @Test
    public void tapNotifiesListenerWithStopIndex() {
        final int[] notified = {-1};
        view.setOnStopSelectedListener(index -> notified[0] = index);

        view.performTapAt(WIDTH);

        assertEquals(5, notified[0]);
        assertEquals("탭은 활성 인덱스도 옮긴다", 5, view.getActiveIndex());
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.TimelineScrubberViewTest"`
Expected: 컴파일 실패 — `cannot find symbol: class TimelineScrubberView`.

- [ ] **Step 4: TimelineScrubberView 작성**

`android/app/src/main/java/com/traveltrace/app/ui/map/TimelineScrubberView.java` (신규):

```java
package com.traveltrace.app.ui.map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.traveltrace.app.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 리플레이 타임라인 스크러버 (프로토타입 하단 시트의 도트+연결선).
 *
 * <p>레이아웃 규칙은 프로토타입과 동일하다: 정차점 N개가 가로를 N등분하고, 각 슬롯은
 * [연결선 + 도트] 순서다. 지나온 구간(index &lt;= active)은 브랜드 색, 남은 구간은 회색.
 * 활성 도트만 커지고, AI 정차점의 미방문 도트는 점선 테두리를 쓴다.
 */
public class TimelineScrubberView extends View {

    public interface OnStopSelectedListener {
        void onStopSelected(int index);
    }

    public static final int NO_SELECTION = -1;

    private final List<MapUiState.Stop> stops = new ArrayList<>();
    private int activeIndex = 0;
    @Nullable private OnStopSelectedListener listener;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int colorBrand;
    private final int colorTrack;
    private final int colorSurface;
    private final int colorApprox;
    private final float lineHeight;
    private final float dotRadius;
    private final float dotRadiusActive;

    public TimelineScrubberView(Context context) {
        this(context, null);
    }

    public TimelineScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        colorBrand = ContextCompat.getColor(context, R.color.fill_brand);
        colorTrack = ContextCompat.getColor(context, R.color.border_default);
        colorSurface = ContextCompat.getColor(context, R.color.surface);
        colorApprox = ContextCompat.getColor(context, R.color.location_approx);

        lineHeight = res(R.dimen.scrubber_line_height);
        dotRadius = res(R.dimen.scrubber_dot) / 2f;
        dotRadiusActive = res(R.dimen.scrubber_dot_active) / 2f;

        linePaint.setStyle(Paint.Style.FILL);
        dotFillPaint.setStyle(Paint.Style.FILL);
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(res(R.dimen.scrubber_dot_stroke));
    }

    private float res(int dimenRes) {
        return getResources().getDimensionPixelSize(dimenRes);
    }

    public void setStops(List<MapUiState.Stop> next) {
        stops.clear();
        stops.addAll(next);
        setActiveIndex(activeIndex);
        invalidate();
    }

    public void setActiveIndex(int index) {
        int max = Math.max(0, stops.size() - 1);
        activeIndex = Math.min(Math.max(index, 0), max);
        invalidate();
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setOnStopSelectedListener(@Nullable OnStopSelectedListener l) {
        listener = l;
    }

    /** x 좌표가 속한 정차점 인덱스. 정차점이 없으면 NO_SELECTION. */
    public int indexAt(float x) {
        if (stops.isEmpty()) return NO_SELECTION;
        float slot = getWidth() / (float) stops.size();
        if (slot <= 0f) return NO_SELECTION;
        int index = (int) (x / slot);
        return Math.min(Math.max(index, 0), stops.size() - 1);
    }

    /** 탭 판정을 테스트에서 직접 호출할 수 있게 분리 (터치 이벤트 합성 불필요). */
    public void performTapAt(float x) {
        int index = indexAt(x);
        if (index == NO_SELECTION) return;
        setActiveIndex(index);
        if (listener != null) listener.onStopSelected(index);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performTapAt(event.getX());
            performClick();
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (stops.isEmpty()) return;

        float slot = getWidth() / (float) stops.size();
        float cy = getHeight() / 2f;

        for (int i = 0; i < stops.size(); i++) {
            boolean reached = i <= activeIndex;
            boolean active = i == activeIndex;

            float slotStart = slot * i;
            float dotCx = slotStart + slot - dotRadiusActive;

            // 연결선: 슬롯 시작 ~ 도트 앞
            linePaint.setColor(reached ? colorBrand : colorTrack);
            canvas.drawRoundRect(slotStart, cy - lineHeight / 2f, dotCx, cy + lineHeight / 2f,
                    lineHeight / 2f, lineHeight / 2f, linePaint);

            float r = active ? dotRadiusActive : dotRadius;
            if (reached) {
                dotFillPaint.setColor(colorBrand);
                canvas.drawCircle(dotCx, cy, r, dotFillPaint);
                dotStrokePaint.setColor(colorSurface);
                dotStrokePaint.setPathEffect(null);
            } else {
                dotFillPaint.setColor(colorSurface);
                canvas.drawCircle(dotCx, cy, r, dotFillPaint);
                dotStrokePaint.setColor(stops.get(i).ai ? colorApprox : colorTrack);
                // AI 근사 정차점은 점선 테두리 (프로토타입 dotBorder: 2px dashed).
                dotStrokePaint.setPathEffect(stops.get(i).ai
                        ? new DashPathEffect(new float[]{4f, 3f}, 0f)
                        : null);
            }
            canvas.drawCircle(dotCx, cy, r, dotStrokePaint);
        }
    }
}
```

> `DashPathEffect` 는 하드웨어 가속에서 원 테두리에 정상 적용된다 — VectorDrawable 의 점선 제약(계획 상단 격차 목록)은 여기 해당하지 않는다.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.TimelineScrubberViewTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: 커밋**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map/TimelineScrubberView.java android/app/src/main/res/values/dimens.xml android/app/src/test/java/com/traveltrace/app/ui/map/TimelineScrubberViewTest.java
git commit -m "feat: add timeline scrubber custom view"
```

---

### Task 9: MAP — 하단 시트 (사진 배너 · 스크러버 · 컨트롤 · 속도)

프로토타입 `screen:'map'` + `notCinema` 의 상시 하단 시트. 재생 버튼은 **아이콘만 토글**하고 실제 리플레이는 돌리지 않는다(로직 에픽 13).

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/drawable/ic_cinema.xml`
- Create: `android/app/src/main/res/drawable/bg_scrim_photo_banner.xml`
- Create: `android/app/src/main/res/drawable/bg_map_sheet.xml`
- Create: `android/app/src/main/res/drawable/bg_pill_white.xml`
- Create: `android/app/src/main/res/drawable/bg_circle_play.xml`
- Create: `android/app/src/main/res/drawable/bg_speed_selected.xml`
- Create: `android/app/src/main/res/layout/view_map_bottom_sheet.xml`
- Modify: `android/app/src/main/res/layout/fragment_map_replay.xml`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapSheetRendererTest.java`

**Interfaces:**
- Consumes: `MapUiState` / `.activeStop()` / `.Speed` (Task 2), `TimelineScrubberView` (Task 8), `MapRenderer.renderTopBar` (Task 7), `ToastPresenter` (Task 3)
- Produces:
  - `MapRenderer.renderSheet(ViewMapBottomSheetBinding binding, MapUiState state)` — static void.
  - `MapReplayViewModel`: `void togglePlay()`, `void setSpeed(MapUiState.Speed)`, `void jumpTo(int index)`, `void next()`, `void prev()`, `void setCinema(boolean)`, `void detachActive()`
  - `MapUiState.Stop` 의 `extra`/`ai`/`toneColor` 소비 지점 확정.

- [ ] **Step 1: 문자열·치수·색 추가**

`android/app/src/main/res/values/strings.xml` — 추가:

```xml
    <!-- MAP · 하단 시트 -->
    <string name="map_badge_gps">GPS</string>
    <string name="map_badge_approx">근사 위치</string>
    <string name="map_detach">빼기</string>
    <string name="map_extra_photos">+%1$d장</string>
    <string name="map_stop_meta">%1$s · %2$d/%3$d번째</string>
    <string name="map_cinema_mode">상영 모드</string>
    <string name="map_speed_relaxed">느긋이</string>
    <string name="map_speed_normal">보통</string>
    <string name="map_speed_fast">빠르게</string>
    <string name="map_play_desc">재생</string>
    <string name="map_pause_desc">일시정지</string>
    <string name="map_prev_desc">이전 장소</string>
    <string name="map_next_desc">다음 장소</string>
    <string name="map_detach_toast">핀을 위치 미상으로 옮겼어요</string>
```

`android/app/src/main/res/values/dimens.xml` — 추가:

```xml
    <!-- MAP 하단 시트 (프로토타입 실측) -->
    <dimen name="photo_banner_height">180dp</dimen>
    <dimen name="map_badge_height">26dp</dimen>
    <dimen name="play_button_size">58dp</dimen>
    <dimen name="speed_pill_height">30dp</dimen>
    <dimen name="control_button_size">40dp</dimen>
    <dimen name="text_stop_name">21sp</dimen>
```

`android/app/src/main/res/values/colors.xml` — 추가:

```xml
    <!-- MAP 사진 배너 스크림: 아래로 갈수록 어두워짐 (프로토타입 to top, rgba(0,0,0,.45)) -->
    <color name="photo_scrim_bottom">#73000000</color>
    <!-- GPS 배지 fill: rgba(49,130,246,.95) -->
    <color name="badge_gps_fill">#F23182F6</color>
    <!-- 배너 위 흰 pill: rgba(255,255,255,.92) -->
    <color name="pill_white_bg">#EBFFFFFF</color>
    <!-- 그라데이션의 투명 정지점 (하드코딩 hex 금지 규칙 준수) -->
    <color name="transparent">#00000000</color>
    <!-- 배너/상영 카드 위 흰 글자의 그림자 (프로토타입 text-shadow 실측) -->
    <color name="text_shadow_soft">#4D000000</color>
    <color name="text_shadow_strong">#59000000</color>
```

- [ ] **Step 2: 드로어블 작성**

`android/app/src/main/res/drawable/ic_cinema.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 상영 모드 아이콘 (프로토타입 24x24 path). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="17dp"
    android:height="17dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,5.5A1.5,1.5 0,0 1,5.5 4h13A1.5,1.5 0,0 1,20 5.5v9A1.5,1.5 0,0 1,18.5 16h-13A1.5,1.5 0,0 1,4 14.5v-9ZM7,19a0.75,0.75 0,0 0,0 1.5h10a0.75,0.75 0,0 0,0 -1.5H7Z" />
</vector>
```

`android/app/src/main/res/drawable/bg_scrim_photo_banner.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 사진 배너 스크림: 위 투명 → 아래 검정 45% (텍스트 가독성). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="90"
        android:startColor="@color/photo_scrim_bottom"
        android:centerColor="@color/transparent"
        android:endColor="@color/transparent"
        android:centerY="0.55" />
</shape>
```

`android/app/src/main/res/drawable/bg_map_sheet.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- MAP 상시 하단 시트: 위 모서리만 24dp. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners
        android:topLeftRadius="@dimen/sheet_corner_map"
        android:topRightRadius="@dimen/sheet_corner_map" />
</shape>
```

`android/app/src/main/res/drawable/bg_pill_white.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 배너 위 흰 pill ("빼기" / "+N장"). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/pill_white_bg" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

`android/app/src/main/res/drawable/bg_circle_play.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 재생/일시정지 버튼: 58dp 파란 원. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/fill_brand" />
</shape>
```

`android/app/src/main/res/drawable/bg_speed_selected.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 선택된 속도 프리셋: 흰 pill (컨테이너는 bg_base). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

`android/app/src/main/res/drawable/badge_gps.xml` 은 이미 있지만 fill 이 불투명 `@color/fill_brand` 다. 프로토타입 GPS 배지는 `rgba(49,130,246,.95)` 이므로 Step 1에서 추가한 `badge_gps_fill` 로 교체한다 — 전체 교체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- GPS 배지: 브랜드 파랑 캡슐 (프로토타입 실측 rgba(49,130,246,.95) — 살짝 비침) -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/badge_gps_fill" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

- [ ] **Step 3: 하단 시트 레이아웃 작성**

`android/app/src/main/res/layout/view_map_bottom_sheet.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- MAP 하단 시트: 핸들 + 사진 배너 + 스크러버 + 컨트롤 + 속도 프리셋. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/sheetRoot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_map_sheet"
    android:elevation="8dp"
    android:orientation="vertical"
    android:paddingStart="16dp"
    android:paddingTop="14dp"
    android:paddingEnd="16dp"
    android:paddingBottom="24dp">

    <View
        android:layout_width="@dimen/sheet_handle_width"
        android:layout_height="@dimen/sheet_handle_height"
        android:layout_gravity="center_horizontal"
        android:layout_marginBottom="14dp"
        android:background="@drawable/handle_bottom_sheet" />

    <!-- 사진 배너 -->
    <FrameLayout
        android:id="@+id/photoBanner"
        android:layout_width="match_parent"
        android:layout_height="@dimen/photo_banner_height">

        <View
            android:id="@+id/photoTone"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <View
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/bg_scrim_photo_banner" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|end"
            android:layout_margin="11dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/badgeGps"
                style="@style/Widget.TravelTrace.Badge.Gps"
                android:layout_height="@dimen/map_badge_height"
                android:drawablePadding="4dp"
                android:text="@string/map_badge_gps"
                app:drawableStartCompat="@drawable/ic_pin"
                app:drawableTint="@color/text_on_fill" />

            <TextView
                android:id="@+id/badgeApprox"
                style="@style/Widget.TravelTrace.Badge.Approx"
                android:layout_height="@dimen/map_badge_height"
                android:text="@string/map_badge_approx"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/detachButton"
                style="@style/TextAppearance.TravelTrace.Small"
                android:layout_width="wrap_content"
                android:layout_height="@dimen/map_badge_height"
                android:layout_marginStart="6dp"
                android:background="@drawable/bg_pill_white"
                android:gravity="center"
                android:paddingStart="11dp"
                android:paddingEnd="11dp"
                android:text="@string/map_detach"
                android:textColor="@color/text_secondary"
                android:textFontWeight="800"
                android:visibility="gone"
                tools:visibility="visible" />
        </LinearLayout>

        <TextView
            android:id="@+id/extraBadge"
            style="@style/TextAppearance.TravelTrace.Small"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/map_badge_height"
            android:layout_gravity="bottom|end"
            android:layout_marginEnd="11dp"
            android:layout_marginBottom="12dp"
            android:background="@drawable/bg_pill_white"
            android:gravity="center"
            android:paddingStart="10dp"
            android:paddingEnd="10dp"
            android:textColor="@color/text_secondary"
            android:textFontWeight="800"
            android:visibility="gone"
            tools:text="+4장"
            tools:visibility="visible" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|start"
            android:layout_marginStart="14dp"
            android:layout_marginBottom="12dp"
            android:orientation="vertical">

            <TextView
                android:id="@+id/stopName"
                style="@style/TextAppearance.TravelTrace.Title2"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:shadowColor="@color/text_shadow_soft"
                android:shadowDy="1"
                android:shadowRadius="4"
                android:textColor="@color/text_on_fill"
                android:textSize="@dimen/text_stop_name"
                tools:text="개선문" />

            <TextView
                android:id="@+id/stopMeta"
                style="@style/TextAppearance.TravelTrace.Numeric"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textColor="@color/text_on_fill"
                android:textFontWeight="600"
                android:textSize="@dimen/text_caption"
                tools:text="10:12 · 1/6번째" />
        </LinearLayout>
    </FrameLayout>

    <com.traveltrace.app.ui.map.TimelineScrubberView
        android:id="@+id/scrubber"
        android:layout_width="match_parent"
        android:layout_height="@dimen/scrubber_height"
        android:layout_marginStart="4dp"
        android:layout_marginTop="16dp"
        android:layout_marginEnd="4dp" />

    <!-- 컨트롤 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="14dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/cinemaButton"
            style="@style/TextAppearance.TravelTrace.Caption"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/control_button_size"
            android:layout_weight="1"
            android:background="@drawable/bg_pill_glass"
            android:backgroundTint="@color/fill_neutral"
            android:drawablePadding="5dp"
            android:gravity="center_vertical"
            android:paddingStart="13dp"
            android:paddingEnd="13dp"
            android:text="@string/map_cinema_mode"
            android:textColor="@color/text_secondary"
            android:textFontWeight="700"
            app:drawableStartCompat="@drawable/ic_cinema"
            app:drawableTint="@color/text_secondary" />

        <ImageView
            android:id="@+id/prevButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:contentDescription="@string/map_prev_desc"
            android:src="@drawable/ic_prev" />

        <FrameLayout
            android:layout_width="@dimen/play_button_size"
            android:layout_height="@dimen/play_button_size"
            android:layout_marginStart="18dp"
            android:layout_marginEnd="18dp">

            <ImageView
                android:id="@+id/playButton"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@drawable/bg_circle_play"
                android:contentDescription="@string/map_play_desc"
                android:elevation="6dp"
                android:padding="17dp"
                android:src="@drawable/ic_play"
                app:tint="@color/text_on_fill" />
        </FrameLayout>

        <ImageView
            android:id="@+id/nextButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:contentDescription="@string/map_next_desc"
            android:src="@drawable/ic_next" />

        <Space
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />
    </LinearLayout>

    <!-- 속도 프리셋 -->
    <LinearLayout
        android:id="@+id/speedGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="14dp"
        android:background="@drawable/bg_pill_glass"
        android:backgroundTint="@color/bg_base"
        android:orientation="horizontal"
        android:padding="3dp">

        <TextView
            android:id="@+id/speedRelaxed"
            style="@style/TextAppearance.TravelTrace.Small"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/speed_pill_height"
            android:gravity="center"
            android:paddingStart="14dp"
            android:paddingEnd="14dp"
            android:text="@string/map_speed_relaxed"
            android:textFontWeight="700" />

        <TextView
            android:id="@+id/speedNormal"
            style="@style/TextAppearance.TravelTrace.Small"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/speed_pill_height"
            android:gravity="center"
            android:paddingStart="14dp"
            android:paddingEnd="14dp"
            android:text="@string/map_speed_normal"
            android:textFontWeight="700" />

        <TextView
            android:id="@+id/speedFast"
            style="@style/TextAppearance.TravelTrace.Small"
            android:layout_width="wrap_content"
            android:layout_height="@dimen/speed_pill_height"
            android:gravity="center"
            android:paddingStart="14dp"
            android:paddingEnd="14dp"
            android:text="@string/map_speed_fast"
            android:textFontWeight="700" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 4: MAP 레이아웃에 시트 붙이기**

`android/app/src/main/res/layout/fragment_map_replay.xml` — `toastPill` **앞**에 include 를 추가한다:

```xml
    <include
        android:id="@+id/mapBottomSheet"
        layout="@layout/view_map_bottom_sheet"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />
```

- [ ] **Step 5: 실패하는 시트 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/map/MapSheetRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapSheetRendererTest {

    private Context ctx;
    private ViewMapBottomSheetBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewMapBottomSheetBinding.inflate(LayoutInflater.from(ctx));
    }

    /** index 를 바꾼 상태 사본. */
    private static MapUiState at(int index) {
        MapUiState b = ScreenFixtures.map();
        return new MapUiState(b.tripTitle, b.unknownCount, b.stops, index,
                b.playing, b.satellite, b.cinema, b.speed);
    }

    @Test
    public void gpsStop_showsGpsBadgeAndHidesApproxAndDetach() {
        MapRenderer.renderSheet(binding, at(0));

        assertEquals("개선문", binding.stopName.getText().toString());
        assertEquals("10:12 · 1/6번째", binding.stopMeta.getText().toString());
        assertEquals(View.VISIBLE, binding.badgeGps.getVisibility());
        assertEquals(View.GONE, binding.badgeApprox.getVisibility());
        assertEquals(View.GONE, binding.detachButton.getVisibility());
    }

    @Test
    public void aiStop_showsApproxBadgeAndDetachButton() {
        MapRenderer.renderSheet(binding, at(3)); // 루브르 = AI

        assertEquals("루브르 박물관", binding.stopName.getText().toString());
        assertEquals(View.GONE, binding.badgeGps.getVisibility());
        assertEquals(View.VISIBLE, binding.badgeApprox.getVisibility());
        assertEquals(View.VISIBLE, binding.detachButton.getVisibility());
    }

    @Test
    public void extraBadgeShowsOnlyWhenStopHasExtraPhotos() {
        MapRenderer.renderSheet(binding, at(0)); // 개선문 extra=0
        assertEquals(View.GONE, binding.extraBadge.getVisibility());

        MapRenderer.renderSheet(binding, at(1)); // 에펠탑 extra=4
        assertEquals(View.VISIBLE, binding.extraBadge.getVisibility());
        assertEquals("+4장", binding.extraBadge.getText().toString());
    }

    @Test
    public void scrubberTracksActiveIndex() {
        MapRenderer.renderSheet(binding, at(2));
        assertEquals(2, binding.scrubber.getActiveIndex());
    }

    @Test
    public void playIconTogglesWithPlayingState() {
        MapUiState paused = ScreenFixtures.map();
        MapRenderer.renderSheet(binding, paused);
        assertEquals(ctx.getString(R.string.map_play_desc),
                binding.playButton.getContentDescription().toString());

        MapUiState playing = new MapUiState(paused.tripTitle, paused.unknownCount, paused.stops,
                paused.activeIndex, true, paused.satellite, paused.cinema, paused.speed);
        MapRenderer.renderSheet(binding, playing);
        assertEquals(ctx.getString(R.string.map_pause_desc),
                binding.playButton.getContentDescription().toString());
    }

    @Test
    public void normalSpeedIsHighlightedByDefault() {
        MapRenderer.renderSheet(binding, ScreenFixtures.map());

        assertEquals(ctx.getColor(R.color.text_primary), binding.speedNormal.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary),
                binding.speedRelaxed.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary), binding.speedFast.getCurrentTextColor());
    }
}
```

- [ ] **Step 6: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.MapSheetRendererTest"`
Expected: 컴파일 실패 — `cannot find symbol: method renderSheet`.

- [ ] **Step 7: MapRenderer.renderSheet 추가**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java` — `applyTab` 위에 다음 메서드를 추가하고, import 에 `com.traveltrace.app.databinding.ViewMapBottomSheetBinding` 를 더한다:

```java
    public static void renderSheet(ViewMapBottomSheetBinding binding, MapUiState state) {
        Context ctx = binding.getRoot().getContext();
        MapUiState.Stop stop = state.activeStop();

        binding.photoTone.setBackgroundColor(stop.toneColor);
        binding.stopName.setText(stop.name);
        binding.stopMeta.setText(ctx.getString(R.string.map_stop_meta,
                stop.time, state.activeIndex + 1, state.stops.size()));

        binding.badgeGps.setVisibility(stop.ai ? View.GONE : View.VISIBLE);
        binding.badgeApprox.setVisibility(stop.ai ? View.VISIBLE : View.GONE);
        // "빼기"는 AI 근사 위치에만 뜬다 — GPS 좌표는 뺄 이유가 없다.
        binding.detachButton.setVisibility(stop.ai ? View.VISIBLE : View.GONE);

        if (stop.extra > 0) {
            binding.extraBadge.setVisibility(View.VISIBLE);
            binding.extraBadge.setText(ctx.getString(R.string.map_extra_photos, stop.extra));
        } else {
            binding.extraBadge.setVisibility(View.GONE);
        }

        binding.scrubber.setStops(state.stops);
        binding.scrubber.setActiveIndex(state.activeIndex);

        binding.playButton.setImageResource(state.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        binding.playButton.setContentDescription(ctx.getString(
                state.playing ? R.string.map_pause_desc : R.string.map_play_desc));

        // 양 끝에서는 이전/다음을 흐리게 (프로토타입 prevColor/nextColor).
        binding.prevButton.setImageTintList(tint(ctx, state.activeIndex > 0));
        binding.nextButton.setImageTintList(
                tint(ctx, state.activeIndex < state.stops.size() - 1));

        applySpeed(ctx, binding.speedRelaxed, state.speed == MapUiState.Speed.RELAXED);
        applySpeed(ctx, binding.speedNormal, state.speed == MapUiState.Speed.NORMAL);
        applySpeed(ctx, binding.speedFast, state.speed == MapUiState.Speed.FAST);
    }

    private static ColorStateList tint(Context ctx, boolean enabled) {
        return ColorStateList.valueOf(ContextCompat.getColor(ctx,
                enabled ? R.color.text_primary : R.color.border_strong));
    }

    /** 선택 속도 = 흰 pill + primary 글자 / 비선택 = 투명 + tertiary 글자. */
    private static void applySpeed(Context ctx, TextView pill, boolean selected) {
        pill.setBackgroundResource(selected ? R.drawable.bg_speed_selected : 0);
        pill.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.text_primary : R.color.text_tertiary));
    }
```

import 추가:

```java
import android.content.res.ColorStateList;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
```

- [ ] **Step 8: ViewModel 에 컨트롤 동작 추가**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayViewModel.java` — `setSatellite` 아래에 추가:

```java
    /** 재생 아이콘 토글만 — 실제 리플레이 진행은 로직 에픽 13 소관. */
    public void togglePlay() {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, !s.playing, s.satellite, s.cinema, s.speed));
    }

    public void setSpeed(MapUiState.Speed speed) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, s.satellite, s.cinema, speed));
    }

    public void jumpTo(int index) {
        MapUiState s = state.getValue();
        if (s == null) return;
        int clamped = Math.min(Math.max(index, 0), s.stops.size() - 1);
        state.setValue(copy(s, clamped, false, s.satellite, s.cinema, s.speed));
    }

    public void next() {
        MapUiState s = state.getValue();
        if (s != null) jumpTo(s.activeIndex + 1);
    }

    public void prev() {
        MapUiState s = state.getValue();
        if (s != null) jumpTo(s.activeIndex - 1);
    }

    public void setCinema(boolean cinema) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, s.satellite, cinema, s.speed));
    }

    private static MapUiState copy(MapUiState s, int activeIndex, boolean playing,
                                   boolean satellite, boolean cinema, MapUiState.Speed speed) {
        return new MapUiState(s.tripTitle, s.unknownCount, s.stops, activeIndex,
                playing, satellite, cinema, speed);
    }
```

`setSatellite` 도 같은 헬퍼를 쓰도록 본문을 교체한다:

```java
    public void setSatellite(boolean satellite) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, satellite, s.cinema, s.speed));
    }
```

- [ ] **Step 9: Fragment 에서 시트 배선**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java` — `onViewCreated` 의 탭 리스너 아래에 추가:

```java
        ViewMapBottomSheetBinding sheet = binding.mapBottomSheet;
        sheet.playButton.setOnClickListener(v -> vm.togglePlay());
        sheet.prevButton.setOnClickListener(v -> vm.prev());
        sheet.nextButton.setOnClickListener(v -> vm.next());
        sheet.cinemaButton.setOnClickListener(v -> vm.setCinema(true));
        sheet.scrubber.setOnStopSelectedListener(vm::jumpTo);
        sheet.speedRelaxed.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.RELAXED));
        sheet.speedNormal.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.NORMAL));
        sheet.speedFast.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.FAST));
        sheet.detachButton.setOnClickListener(v ->
                ToastPresenter.show(binding.getRoot(), getString(R.string.map_detach_toast)));
```

`render(MapUiState)` 본문에 시트 렌더를 추가:

```java
        MapRenderer.renderSheet(binding.mapBottomSheet, state);
```

import 추가:

```java
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.ui.common.ToastPresenter;
```

> 프로토타입의 `detach` 는 핀을 목록에서 실제로 빼지만, 화면 단계에선 토스트만 띄운다 — 목록 변형은 로직 에픽 13 소관.

- [ ] **Step 10: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.*"`
Expected: PASS (MapTopBarRendererTest 3 + TimelineScrubberViewTest 6 + MapSheetRendererTest 6 = 15 tests).

- [ ] **Step 11: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: implement MAP bottom sheet (photo banner, scrubber, controls, speed)"
```

---

### Task 10: MAP — 상영 모드 오버레이

프로토타입 `cinema`. 하단 시트·상단바를 숨기고 사진 카드만 크게 띄우는 전면 오버레이. 화면 아무 곳이나 누르면 해제된다.

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/dimens.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/drawable/bg_cinema_hint.xml`
- Create: `android/app/src/main/res/layout/view_cinema_overlay.xml`
- Modify: `android/app/src/main/res/layout/fragment_map_replay.xml`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayRendererTest.java`

**Interfaces:**
- Consumes: `MapUiState` / `.activeStop()` / `.cinema` (Task 2), `ScreenFixtures.cityLabel()` (Task 2), `MapReplayViewModel.setCinema(boolean)` (Task 9)
- Produces:
  - `MapRenderer.renderCinema(ViewCinemaOverlayBinding binding, MapUiState state, String cityLabel)` — static void.
  - `fragment_map_replay.xml` 의 `@id/cinemaOverlay` include 지점.

- [ ] **Step 1: 문자열·치수·색 추가**

`android/app/src/main/res/values/strings.xml` — 추가:

```xml
    <!-- MAP · 상영 모드 -->
    <string name="cinema_hint">화면을 누르면 컨트롤이 나와요</string>
    <string name="cinema_meta">%1$s · %2$s</string>
```

`android/app/src/main/res/values/dimens.xml` — 추가:

```xml
    <dimen name="cinema_card_height">340dp</dimen>
    <dimen name="text_cinema_name">26sp</dimen>
```

`android/app/src/main/res/values/colors.xml` — 추가:

```xml
    <!-- 상영 모드: 배경 암전 + 힌트 pill rgba(0,0,0,.4) -->
    <color name="cinema_backdrop">#B3000000</color>
    <color name="cinema_hint_bg">#66000000</color>
    <color name="cinema_hint_text">#D9FFFFFF</color>
```

- [ ] **Step 2: 드로어블·레이아웃 작성**

`android/app/src/main/res/drawable/bg_cinema_hint.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 상영 모드 힌트 pill. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/cinema_hint_bg" />
    <corners android:radius="@dimen/radius_full" />
</shape>
```

`android/app/src/main/res/layout/view_cinema_overlay.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 상영 모드: 암전 배경 + 88% 카드 + 힌트. 어디를 눌러도 해제된다. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/cinemaRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/cinema_backdrop"
    android:clickable="true"
    android:focusable="true"
    android:gravity="center_horizontal|bottom"
    android:orientation="vertical"
    android:paddingBottom="60dp">

    <FrameLayout
        android:id="@+id/cinemaCard"
        android:layout_width="0dp"
        android:layout_height="@dimen/cinema_card_height"
        android:layout_gravity="center_horizontal"
        android:layout_marginStart="24dp"
        android:layout_marginEnd="24dp">

        <View
            android:id="@+id/cinemaTone"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <View
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/bg_scrim_photo_banner" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|start"
            android:layout_marginStart="18dp"
            android:layout_marginBottom="18dp"
            android:orientation="vertical">

            <TextView
                android:id="@+id/cinemaName"
                style="@style/TextAppearance.TravelTrace.Display"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:shadowColor="@color/text_shadow_strong"
                android:shadowDy="1"
                android:shadowRadius="6"
                android:textColor="@color/text_on_fill"
                android:textSize="@dimen/text_cinema_name"
                tools:text="개선문" />

            <TextView
                android:id="@+id/cinemaMeta"
                style="@style/TextAppearance.TravelTrace.Numeric"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="3dp"
                android:textColor="@color/text_on_fill"
                android:textFontWeight="600"
                android:textSize="@dimen/text_label"
                tools:text="10:12 · 파리" />
        </LinearLayout>
    </FrameLayout>

    <TextView
        android:id="@+id/cinemaHint"
        style="@style/TextAppearance.TravelTrace.Small"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="18dp"
        android:background="@drawable/bg_cinema_hint"
        android:paddingStart="14dp"
        android:paddingTop="7dp"
        android:paddingEnd="14dp"
        android:paddingBottom="7dp"
        android:text="@string/cinema_hint"
        android:textColor="@color/cinema_hint_text"
        android:textFontWeight="600" />
</LinearLayout>
```

> 프로토타입 카드는 화면 폭의 88% 다. 390dp 기준 좌우 24dp 마진이 이에 해당한다(390 - 48 = 342 ≈ 87.7%). 카드 모서리 20dp 는 Step 4에서 `ViewOutlineProvider` 대신 `bg_map_sheet` 계열 배경으로 처리하지 않고, `cinemaCard` 에 `clipToOutline` 을 주는 대신 **톤 View 에 radius 배경을 코드로 지정**한다 — Step 4 참고.

- [ ] **Step 3: 실패하는 렌더 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/map/CinemaOverlayRendererTest.java` (신규):

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewCinemaOverlayBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CinemaOverlayRendererTest {

    private ViewCinemaOverlayBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewCinemaOverlayBinding.inflate(LayoutInflater.from(ctx));
    }

    private static MapUiState cinemaAt(int index) {
        MapUiState b = ScreenFixtures.map();
        return new MapUiState(b.tripTitle, b.unknownCount, b.stops, index,
                b.playing, b.satellite, true, b.speed);
    }

    @Test
    public void hiddenWhenCinemaIsOff() {
        MapRenderer.renderCinema(binding, ScreenFixtures.map(), ScreenFixtures.cityLabel());
        assertEquals(View.GONE, binding.cinemaRoot.getVisibility());
    }

    @Test
    public void visibleWithActiveStopNameAndCityMeta() {
        MapRenderer.renderCinema(binding, cinemaAt(0), ScreenFixtures.cityLabel());

        assertEquals(View.VISIBLE, binding.cinemaRoot.getVisibility());
        assertEquals("개선문", binding.cinemaName.getText().toString());
        assertEquals("10:12 · 파리", binding.cinemaMeta.getText().toString());
    }

    @Test
    public void followsActiveStop() {
        MapRenderer.renderCinema(binding, cinemaAt(5), ScreenFixtures.cityLabel());

        assertEquals("몽마르트", binding.cinemaName.getText().toString());
        assertEquals("18:30 · 파리", binding.cinemaMeta.getText().toString());
    }
}
```

- [ ] **Step 4: MapRenderer.renderCinema 추가**

`android/app/src/main/java/com/traveltrace/app/ui/map/MapRenderer.java` — `applySpeed` 위에 추가:

```java
    public static void renderCinema(ViewCinemaOverlayBinding binding, MapUiState state,
                                    String cityLabel) {
        binding.cinemaRoot.setVisibility(state.cinema ? View.VISIBLE : View.GONE);
        if (!state.cinema) return;

        Context ctx = binding.getRoot().getContext();
        MapUiState.Stop stop = state.activeStop();

        // 톤 배경 + 20dp 라운드: 톤 색이 상태마다 달라 드로어블 리소스로 고정할 수 없다.
        GradientDrawable tone = new GradientDrawable();
        tone.setColor(stop.toneColor);
        tone.setCornerRadius(ctx.getResources().getDimension(R.dimen.radius_20));
        binding.cinemaTone.setBackground(tone);
        binding.cinemaCard.setClipToOutline(true);

        binding.cinemaName.setText(stop.name);
        binding.cinemaMeta.setText(ctx.getString(R.string.cinema_meta, stop.time, cityLabel));
    }
```

import 추가:

```java
import android.graphics.drawable.GradientDrawable;
import com.traveltrace.app.databinding.ViewCinemaOverlayBinding;
```

- [ ] **Step 5: MAP 레이아웃에 오버레이 붙이기 + 배선**

`android/app/src/main/res/layout/fragment_map_replay.xml` — `toastPill` **앞**에 추가 (시트보다 위, 토스트보다 아래):

```xml
    <include
        android:id="@+id/cinemaOverlay"
        layout="@layout/view_cinema_overlay"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />
```

`MapReplayFragment.onViewCreated` — 시트 배선 아래에 추가:

```java
        binding.cinemaOverlay.cinemaRoot.setOnClickListener(v -> vm.setCinema(false));
```

`MapReplayFragment.render(MapUiState)` — 본문에 추가. 상영 모드에서는 상단바·시트를 숨긴다(프로토타입 `notCinema`):

```java
        MapRenderer.renderCinema(binding.cinemaOverlay, state, ScreenFixtures.cityLabel());
        int chromeVis = state.cinema ? View.GONE : View.VISIBLE;
        binding.mapTopBar.topBarRoot.setVisibility(chromeVis);
        binding.mapBottomSheet.sheetRoot.setVisibility(chromeVis);
```

import 추가:

```java
import com.traveltrace.app.ui.preview.ScreenFixtures;
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.CinemaOverlayRendererTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: add cinema mode overlay to MAP"
```

---

### Task 11: MAP — 위치 미상 드로어

프로토타입 `sheet:'unknown'`. 상단 "위치 미상 5" 칩을 누르면 열리는 모달 바텀시트. 마지막 화면이다.

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/layout/sheet_unknown_photos.xml`
- Create: `android/app/src/main/java/com/traveltrace/app/ui/map/UnknownPhotosSheetFragment.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapReplayFragment.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/UnknownPhotosSheetTest.java`

**Interfaces:**
- Consumes: `ScreenFixtures.unknownThumbTones()` (Task 2), 앱 테마의 `bottomSheetDialogTheme`
- Produces:
  - `UnknownPhotosSheetFragment.newInstance(int count)` → `UnknownPhotosSheetFragment`
  - `UnknownPhotosSheetFragment.TAG` (String, `"unknown_sheet"`)
  - `UnknownPhotosSheetFragment.bindContent(SheetUnknownPhotosBinding binding, int count, int[] tones)` — static, 다이얼로그 없이 테스트 가능.

- [ ] **Step 1: 문자열 추가**

`android/app/src/main/res/values/strings.xml` — 추가:

```xml
    <!-- MAP · 위치 미상 드로어 -->
    <string name="unknown_sheet_title">위치 미상 · %1$d장</string>
    <string name="unknown_sheet_body">GPS도 없고 장소도 알아내지 못한 사진이에요. 시각이 있는 사진은 경로 흐름에 끼워 보여주고, 나머지는 마지막 에필로그에 모아둬요.</string>
    <string name="unknown_sheet_close">닫기</string>
```

- [ ] **Step 2: 드로어 레이아웃 작성**

`android/app/src/main/res/layout/sheet_unknown_photos.xml` (신규):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 위치 미상 드로어: 핸들 + 제목 + 본문 + 4열 썸네일 + 닫기.
     썸네일 5개는 고정 개수라 GridLayout 으로 충분하다(RecyclerView 불필요). -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/surface"
    android:orientation="vertical"
    android:paddingStart="20dp"
    android:paddingTop="22dp"
    android:paddingEnd="20dp"
    android:paddingBottom="28dp">

    <View
        android:layout_width="@dimen/sheet_handle_width"
        android:layout_height="@dimen/sheet_handle_height"
        android:layout_gravity="center_horizontal"
        android:layout_marginBottom="16dp"
        android:background="@drawable/handle_bottom_sheet" />

    <TextView
        android:id="@+id/unknownTitle"
        style="@style/TextAppearance.TravelTrace.Title2"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="@dimen/text_empty_title"
        tools:text="위치 미상 · 5장" />

    <TextView
        style="@style/TextAppearance.TravelTrace.Caption"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:text="@string/unknown_sheet_body"
        android:textColor="@color/text_secondary"
        android:textFontWeight="500" />

    <GridLayout
        android:id="@+id/unknownGrid"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="18dp"
        android:columnCount="4" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/unknownClose"
        style="@style/Widget.TravelTrace.Button.Neutral"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:layout_marginTop="20dp"
        android:text="@string/unknown_sheet_close"
        app:cornerRadius="@dimen/radius_13" />
</LinearLayout>
```

- [ ] **Step 3: 실패하는 테스트 작성**

`android/app/src/test/java/com/traveltrace/app/ui/map/UnknownPhotosSheetTest.java` (신규):

```java
package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetUnknownPhotosBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class UnknownPhotosSheetTest {

    private SheetUnknownPhotosBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = SheetUnknownPhotosBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void titleShowsCount() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals("위치 미상 · 5장", binding.unknownTitle.getText().toString());
    }

    @Test
    public void addsOneThumbPerTone() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals(5, binding.unknownGrid.getChildCount());
    }

    @Test
    public void rebindDoesNotDuplicateThumbs() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals(5, binding.unknownGrid.getChildCount());
    }
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.traveltrace.app.ui.map.UnknownPhotosSheetTest"`
Expected: 컴파일 실패 — `cannot find symbol: class UnknownPhotosSheetFragment`.

- [ ] **Step 5: UnknownPhotosSheetFragment 작성**

`android/app/src/main/java/com/traveltrace/app/ui/map/UnknownPhotosSheetFragment.java` (신규):

```java
package com.traveltrace.app.ui.map;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetUnknownPhotosBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

/** 위치 미상 드로어 (프로토타입 sheet:'unknown'). */
public class UnknownPhotosSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "unknown_sheet";
    private static final String ARG_COUNT = "count";
    private static final int COLUMNS = 4;

    private SheetUnknownPhotosBinding binding;

    public static UnknownPhotosSheetFragment newInstance(int count) {
        UnknownPhotosSheetFragment f = new UnknownPhotosSheetFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_COUNT, count);
        f.setArguments(args);
        return f;
    }

    /**
     * 콘텐츠 렌더만 분리 — 다이얼로그 없이 테스트할 수 있다.
     * 실제 썸네일은 로직 단계에서 붙고, 지금은 톤 색 타일로 대체한다.
     */
    public static void bindContent(SheetUnknownPhotosBinding binding, int count, int[] tones) {
        Context ctx = binding.getRoot().getContext();
        binding.unknownTitle.setText(ctx.getString(R.string.unknown_sheet_title, count));

        // 재바인딩 시 타일이 쌓이지 않도록 먼저 비운다.
        binding.unknownGrid.removeAllViews();

        int gap = ctx.getResources().getDimensionPixelSize(R.dimen.space_2);
        int radius = ctx.getResources().getDimensionPixelSize(R.dimen.radius_11);

        for (int i = 0; i < tones.length; i++) {
            View tile = new View(ctx);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(tones[i]);
            bg.setCornerRadius(radius);
            tile.setBackground(bg);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % COLUMNS, 1f);
            lp.rowSpec = GridLayout.spec(i / COLUMNS);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            tile.setLayoutParams(lp);

            binding.unknownGrid.addView(tile);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetUnknownPhotosBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindContent(binding, requireArguments().getInt(ARG_COUNT),
                ScreenFixtures.unknownThumbTones());
        binding.unknownClose.setOnClickListener(v -> dismiss());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
```

> 정사각 타일은 `GridLayout` 이 폭을 4등분해 정하고 높이는 래핑된다. 프로토타입의 `aspect-ratio:1` 을 맞추려면 Step 6에서 높이를 폭에 맞춘다.

- [ ] **Step 6: 타일을 정사각으로 맞추기**

`bindContent` 의 `binding.unknownGrid.addView(tile);` **아래**에, 그리드 폭이 정해진 뒤 높이를 폭과 같게 만드는 후처리를 추가한다:

```java
        }

        // GridLayout 은 폭만 4등분한다 → 레이아웃 확정 후 각 타일 높이를 폭에 맞춰 정사각으로.
        binding.unknownGrid.post(() -> {
            for (int i = 0; i < binding.unknownGrid.getChildCount(); i++) {
                View tile = binding.unknownGrid.getChildAt(i);
                if (tile.getWidth() > 0 && tile.getHeight() != tile.getWidth()) {
                    ViewGroup.LayoutParams lp = tile.getLayoutParams();
                    lp.height = tile.getWidth();
                    tile.setLayoutParams(lp);
                }
            }
        });
```

> `post` 는 Robolectric 에서 즉시 실행되지 않지만, 위 테스트는 자식 **개수**만 검증하므로 영향이 없다.

- [ ] **Step 7: 칩에서 드로어 열기**

`MapReplayFragment.onViewCreated` — 상단바 배선 아래에 추가:

```java
        binding.mapTopBar.unknownChip.setOnClickListener(v -> {
            MapUiState state = vm.state().getValue();
            if (state == null) return;
            UnknownPhotosSheetFragment.newInstance(state.unknownCount)
                    .show(getChildFragmentManager(), UnknownPhotosSheetFragment.TAG);
        });
```

- [ ] **Step 8: 전체 테스트 실행 — 통과 확인**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS — 전 화면 렌더 테스트가 모두 통과한다 (하네스 2 + 픽스처 8 + HOME 5 + SELECT 4 + ANALYZE 5 + 타임존 2 + 상단바 3 + 스크러버 6 + 시트 6 + 상영 3 + 드로어 3 = 47 tests).

- [ ] **Step 9: 앱 빌드 + 전체 화면 육안 대조**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. 기기에서 프로토타입과 전 화면을 대조한다:
HOME(목록/빈) → SELECT → ANALYZE(+타임존 시트) → MAP(상단바·시트·스크러버) → 상영 모드 → 위치 미상 드로어 → 토스트.

- [ ] **Step 10: 커밋**

```bash
git add android/app/src/main android/app/src/test/java/com/traveltrace/app/ui/map
git commit -m "feat: add unknown-photos drawer to MAP"
```

---

## 완료 정의 (이 계획 전체)

- [ ] 프로토타입의 화면 12종(인벤토리 표)이 전부 렌더된다.
- [ ] `./gradlew :app:testDebugUnitTest` 전부 통과.
- [ ] `./gradlew :app:assembleDebug` 성공, 기기에서 HOME→SELECT→ANALYZE→MAP 왕복 가능.
- [ ] 화면 코드에 하드코딩된 hex·px·한국어 문자열이 없다(전부 리소스 경유).
- [ ] 사진 로딩·EXIF·AI·지오코딩·리플레이 애니메이션·Room 저장 로직이 **없다** — 데이터는 전부 `ScreenFixtures`.
- [ ] `plan/00-overview.md` 의 공통 DoD(Java·View/XML, 한국어 리소스 분리)를 위반하지 않는다.

## 다음 단계 (이 계획 밖)

로직 에픽은 **`ScreenFixtures` 를 Repository 로 교체**하는 것에서 시작한다. 각 `XxxViewModel` 의 생성자에서 픽스처 호출을 걷어내고 주입된 리포지토리를 관찰하게 만들면, Renderer·레이아웃·테스트는 그대로 둔 채 실데이터로 넘어간다. 이후 `plan/` 의 에픽 05~15가 이어진다.
