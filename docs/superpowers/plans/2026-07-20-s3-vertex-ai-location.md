# S3 — GPS 없는 사진 → Vertex AI 근사 위치 핀 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GPS EXIF 가 없는 사진을 프라이버시 파이프라인(①로컬 EXIF → ②JPEG 재인코딩/다운스케일 → ③업로드)에 태워 Vertex AI Gemini 로 랜드마크·도시 *이름*만 얻고, Places/Geocoding 분기로 좌표화해 지도에 "근사 위치" 핀으로 그린다.

**Architecture:** 새 `analysis/` 패키지가 사진 1장의 전 과정을 소유한다 — `PhotoAnalysisPipeline` 이 EXIF→다운스케일→비전→좌표화 순서를 **타입으로 강제**하고, `AnalysisViewModel` 은 `extractor.extract()` 대신 `pipeline.analyze()` 만 호출하므로 호출부가 순서를 어길 수 없다. AI 호출은 **Vertex AI Express Mode REST**(API 키 헤더, 서비스 계정 없음)를 Retrofit/Gson 으로 때린다 — 새 의존성 0개. 좌표화는 Places Text Search(POI)와 Geocoding(행정 지명)으로 분기하고, 후보가 지리적으로 흩어지면 오답 핀 대신 `NAME_ONLY` 로 강등한다.

**Tech Stack:** Java 17 + View/XML, Retrofit 2.11 + OkHttp 4.12 + Gson 2.11, Hilt(annotationProcessor), Room 2.6, ExecutorService, JUnit4 + Robolectric 4.14.

## Global Constraints

S1 계획서(`docs/superpowers/plans/2026-07-19-s1-gps-route-map.md`)의 Global Constraints 를 **전부 그대로 승계**한다:

- **Java + View/XML만.** Kotlin·Compose·코루틴·KSP·kotlinx.serialization 도입 금지.
- 비동기는 **`ExecutorService`** 고정. JSON은 Gson. DI/Room은 **annotationProcessor**.
- Gradle은 **Groovy DSL**, buildSrc는 Java.
- `compileSdk 35 / targetSdk 35 / minSdk 33`.
- 사용자 대면 문자열은 전부 **`res/values/strings.xml`의 한국어 리소스**. 코드에 한국어 리터럴 금지.
- **UiState 필드를 추가/변경하면 반드시 셋을 함께 확인한다** — ① `ScreenFixtures`의 생성 호출부, ② 같은 클래스의 복제 메서드(`withToggled` 류), ③ 하드코딩 문자열을 어서션하는 Renderer 테스트.
- `ScreenFixtures`는 **`src/main`에 그대로 둔다.**
- API 키는 `local.properties` → `BuildConfig`/manifestPlaceholder로만 주입. 하드코딩 금지.
- 좌표 없는 사진을 **절대 (0,0)에 찍지 않는다.**
- 기존 파일을 고치라는 지시의 **줄 번호는 근사치**다.

S3 가 추가하는 제약:

- **골든 스크린샷 14장 전부 불변.** 이 계획은 `AnalysisUiState` 필드를 바꾸지 않고 `MapRouteRenderer`(스크린샷 테스트 없음)만 건드리므로 재기록 대상이 0장이다. 골든이 깨지면 그건 회귀다.
- **기존 단위 테스트는 회귀 0건.** 단 하나의 예외: `AnalysisPipelineTest` 는 `AnalysisViewModel` 생성자 변경 때문에 **반드시** 수정된다(Task 8). 그 외 테스트를 고쳐야 한다면 설계가 틀린 것이다.
- **`ModelIdGuardTask` 가 `src/main` 의 `.java`/`.xml` 에서 `"gemini-[0-9]`·`"gpt-[0-9]`·`"o[1-9]` 리터럴을 발견하면 빌드가 실패한다.** 모델 ID 는 반드시 `BuildConfig.GEMINI_VISION_MODEL` 로 읽는다. 테스트 코드(`src/test`)는 스캔 대상이 아니므로 리터럴을 써도 된다.
- **AI 는 좌표를 만들지 않는다.** `RecognitionResult` 에 lat/lng 필드가 없는 것이 그 계약이며, 이 계획은 그 타입을 확장하지 않는다.
- **S3 범위 밖(건드리지 말 것):** 동시성 4건·타임아웃·재시도·지수 백오프·429 처리·여행당 비용 상한·`AnalysisCache` 테이블 — 전부 **S4**. OpenAI 폴백·엔진 모드 토글 — **S5**. `NAME_ONLY` 전용 UI·detach — **S6**. S3 는 **단일 프로바이더·직렬 처리**다.
- **네트워크 타임아웃은 OkHttp 기본값을 쓰지 않는다.** S4 가 정책을 세우기 전까지의 안전장치로 connect 10s / read 30s 를 `NetworkModule` 에 박는다(PRD §4.8 의 30s 와 일치).
- Vertex AI 접근은 **Express Mode**(엔드포인트 `https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:generateContent`, 헤더 `x-goog-api-key`)로 고정한다. 서비스 계정 JSON·OAuth2·google-services.json·Firebase SDK 를 도입하지 않는다.

---

## File Structure

### 새로 만드는 파일

| 경로 | 책임 |
|---|---|
| `android/buildSrc/src/main/java/com/traveltrace/build/VertexModel.java` | Vertex publisherModels.list 응답 1건의 최소 투영(`name`, `launchStage`) |
| `android/app/src/main/java/com/traveltrace/app/analysis/UploadPreparer.java` | ② 다운스케일 + JPEG 재인코딩 + SHA-256 콘텐츠 해시. 스트림 1회 읽기 |
| `android/app/src/main/java/com/traveltrace/app/analysis/GeocodeRouter.java` | `RecognitionResult` → `GeocodeQuery`(POI vs 행정 지명) 라우팅. 순수 함수 |
| `android/app/src/main/java/com/traveltrace/app/analysis/Disambiguator.java` | 좌표 후보 다수 → 동명 지명 판정. 순수 함수(하버사인) |
| `android/app/src/main/java/com/traveltrace/app/analysis/AiLocationResolver.java` | 인식 결과 + 지오코딩 → `PhotoAnalysis` 분류 확정(PLACED/NAME_ONLY/UNKNOWN) |
| `android/app/src/main/java/com/traveltrace/app/analysis/PhotoAnalysisPipeline.java` | **①→②→③ 순서를 강제하는 단일 진입점.** 사진 1장 → `PhotoAnalysis` |
| `android/app/src/main/java/com/traveltrace/app/data/vision/VertexApi.java` | Retrofit 인터페이스(`:generateContent`) |
| `android/app/src/main/java/com/traveltrace/app/data/vision/VertexRequestBuilder.java` | 요청 JSON 조립(inlineData + responseSchema + systemInstruction). 순수 |
| `android/app/src/main/java/com/traveltrace/app/data/vision/VertexResponseParser.java` | 응답 JSON → `RecognitionResult`. 스키마 위반 = 인식 실패. 순수 |
| `android/app/src/main/java/com/traveltrace/app/data/vision/VertexGeminiProvider.java` | `VisionProvider` 실구현. 블로킹 `Call.execute()` |
| `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesTextSearchApi.java` | Retrofit — Places API (New) Text Search |
| `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingApi.java` | Retrofit — Geocoding API |
| `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesResponseParser.java` | Places 응답 → `List<GeoPoint>`. 순수 |
| `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingResponseParser.java` | Geocoding 응답 → `List<GeoPoint>`. 순수 |
| `android/app/src/main/java/com/traveltrace/app/data/geocode/RoutingGeocoder.java` | `Geocoder` 실구현. `GeocodeQuery` 서브타입으로 분기 |
| `android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java` | 이 앱 최초의 Retrofit/OkHttp/Gson 인스턴스 3종 |

### 수정하는 파일

| 경로 | 변경 |
|---|---|
| `android/buildSrc/.../Constants.java` | `RULESET_VERSION` → `"2026-07-vertex"` |
| `android/buildSrc/.../VisionModelResolver.java` | `selectGemini(List<GeminiModel>)` → `selectVertex(List<VertexModel>)` |
| `android/buildSrc/.../ResolveVisionModelsTask.java` | Gemini Developer API → Vertex publisherModels.list, 키도 `VERTEX_API_KEY` |
| `android/buildSrc/.../GeminiModel.java` | **삭제**(`VertexModel` 이 대체) |
| `android/app/build.gradle` | `VERTEX_API_KEY` buildConfigField, resolve task 배선 |
| `android/local.properties.example` | `GEMINI_API_KEY` → `VERTEX_API_KEY`, 주석 정정 |
| `android/README.md` | A2 절을 Vertex 기준으로, A3 에 Vertex 키 추가 |
| `.../domain/model/PhotoAnalysis.java` | `landmarkName`·`city`·`country`·`confidence`·`contentHash` 추가 |
| `.../data/repo/RoomPhotoAnalysisRepository.java` | 위 5개 필드를 실제로 저장 |
| `.../data/repo/RoomTripRepository.java` | `unknownCount` 에 `NAME_ONLY` 합산 |
| `.../di/AppModule.java` | 스텁 → `VertexGeminiProvider`·`RoutingGeocoder` 바인딩 |
| `.../data/VisionProviderStub.java`, `GeocoderStub.java` | **삭제** |
| `.../ui/analysis/AnalysisViewModel.java` | `ExifExtractor` → `PhotoAnalysisPipeline`, `countUnknown` 에 NAME_ONLY 합산 |
| `.../ui/map/MapRouteRenderer.java` | AI 핀(`pin_approx`) + AI 구간 점선 폴리라인 |
| `.../app/src/test/.../ui/analysis/AnalysisPipelineTest.java` | 생성자 변경 반영 + 페이크 프로바이더 |

### 이미 존재해 **만들지 않는** 것

- `R.drawable.pin_approx`, `R.string.map_badge_approx`, `map_sheet_ai.png` 골든 — MAP 바텀시트의 AI 배지·"빼기" 버튼 UI 는 S1 에서 완성·스크린샷 테스트까지 끝났다. 실제 AI 데이터가 도달한 적이 없을 뿐이다.
- `photo_locations` 의 `landmarkName`·`city`·`country`·`confidence` 컬럼, `photos.contentHash` 컬럼 — 전부 존재하며 지금은 항상 null 이다. **Room 마이그레이션 불필요.**
- `PhotoLocationDao.stopsFor()` 는 이미 `l.source`·`l.landmarkName` 을 select 하고 `classification = 'PLACED'` 로 거른다. `MapReplayViewModel.toState()` 는 이미 `row.source == LocationSource.AI` 를 `Stop.ai` 로 옮긴다. **DAO·ViewModel 변경 불필요** — 지도 그리기(Task 9)만 남았다.
- `LocationClassification.NAME_ONLY`, `LocationSource.AI` enum 상수 — 이미 정의됨.

---

## Task 1: buildSrc — Vertex 모델 목록으로 확정 로직 이전

Vertex Express 로 호출할 거면 **Vertex 가 실제로 노출하는 모델**을 검증해야 한다. Developer API 목록에는 있으나 Vertex express 에선 404 인 모델이 실재하므로(참조 프로젝트가 `gemini-3.1-pro`/`3.1-flash` 에서 겪음), 조회 표면과 호출 표면을 일치시킨다.

**중요한 한계 — 미리 알고 시작할 것:** Vertex 의 `publisherModels.list` 응답에는 Developer API 의 `supportedGenerationMethods` 같은 **능력 메타데이터가 없다.** `supportedActions` 는 콘솔 UI 용 CallToAction 객체라 `generateContent` 지원 여부를 알려주지 않는다. 따라서 vision 능력 판별은 기존 OpenAI 경로와 **똑같이 버전 관리되는 이름 allowlist** 로 한다. 대신 Vertex 는 `launchStage` 를 주므로 `DEPRECATED` 를 걸러낼 수 있다 — Developer API 경로에는 없던 개선이다.

**Files:**
- Create: `android/buildSrc/src/main/java/com/traveltrace/build/VertexModel.java`
- Delete: `android/buildSrc/src/main/java/com/traveltrace/build/GeminiModel.java`
- Modify: `android/buildSrc/src/main/java/com/traveltrace/build/VisionModelResolver.java`
- Modify: `android/buildSrc/src/main/java/com/traveltrace/build/ResolveVisionModelsTask.java`
- Modify: `android/buildSrc/src/main/java/com/traveltrace/build/Constants.java`
- Modify: `android/build.gradle` (**루트** — `resolveVisionModels` 태스크 등록부가 여기 있다)
- Modify: `android/app/build.gradle` (buildConfigField 만)
- Modify: `android/local.properties.example`, `android/README.md`
- Test: `android/buildSrc/src/test/java/com/traveltrace/build/VisionModelResolverTest.java`

**Interfaces:**
- Consumes: 없음(최초 태스크)
- Produces: `BuildConfig.VERTEX_API_KEY`(String), `BuildConfig.GEMINI_VISION_MODEL`(String, 이제 Vertex 에서 확정된 값). `BuildConfig.OPENAI_VISION_MODEL`·`OPENAI_API_KEY` 는 S5 용으로 **그대로 남는다.**

- [ ] **Step 1: `VertexModel` DTO 를 만든다**

Create `android/buildSrc/src/main/java/com/traveltrace/build/VertexModel.java`:

```java
package com.traveltrace.build;

/**
 * Minimal projection of a Vertex AI publisherModels.list entry
 * (GET https://aiplatform.googleapis.com/v1/publishers/google/models).
 *
 * Note what is NOT here: the Developer API's `supportedGenerationMethods`. Vertex's
 * response carries `supportedActions`, a console-UI CallToAction object that does not
 * state whether the model answers `:generateContent`. Vision capability is therefore
 * decided by the versioned name allowlist in VisionModelResolver, exactly as it already
 * is for OpenAI. `launchStage` is the one real signal Vertex adds — it lets us drop
 * DEPRECATED entries, which the Developer API never exposed.
 */
public final class VertexModel {
    /** Fully qualified, e.g. "publishers/google/models/gemini-2.5-flash". */
    public final String name;
    /** "GA" | "PUBLIC_PREVIEW" | "EXPERIMENTAL" | "DEPRECATED" | null when absent. */
    public final String launchStage;

    public VertexModel(String name, String launchStage) {
        this.name = name;
        this.launchStage = launchStage;
    }
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`android/buildSrc/src/test/java/com/traveltrace/build/VisionModelResolverTest.java` 를 연다. **`selectOpenAi` 만 호출하는 테스트는 건드리지 않는다.**

`GeminiModel` 을 참조하는 테스트를 **전부** 지운다 — `selectGemini(...)` 를 호출하는 것들뿐 아니라 **`selectReturnsBoth()`(대략 60~67줄)도 포함이다.** 이 테스트는 `select(List<GeminiModel>, List<OpenAiModel>)` 오버로드를 호출하는데, Step 4 에서 그 오버로드가 사라지고 Step 1 에서 `GeminiModel.java` 자체가 삭제되므로 그대로 두면 `:buildSrc:test` 가 컴파일조차 되지 않는다. (`selectGemini` 를 호출하지 않는다는 이유로 살려두기 쉬운 자리다.)

그 자리에 아래 Vertex 테스트를 넣는다. 파일 상단 import 에 `java.util.Arrays`·`java.util.Collections` 가 이미 있으면 중복 추가하지 않는다.

`selectReturnsBoth()` 를 대체하는 테스트도 함께 넣는다:

```java
    @Test
    public void selectReturnsBoth() {
        String[] r = VisionModelResolver.select(
                Collections.singletonList(vx("gemini-2.0-flash", "GA")),
                Collections.singletonList(new OpenAiModel("gpt-4o")));
        assertEquals("gemini-2.0-flash", r[0]);
        assertEquals("gpt-4o", r[1]);
    }
```

```java
    private static VertexModel vx(String shortName, String stage) {
        return new VertexModel("publishers/google/models/" + shortName, stage);
    }

    @Test
    public void selectsLexicographicallyNewestVisionModel() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("gemini-2.5-flash", "GA"),
                vx("gemini-1.5-pro", "GA")));
        assertEquals("gemini-2.5-flash", picked);
    }

    @Test
    public void excludesDeprecatedEvenWhenItSortsNewest() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("gemini-9.9-flash", "DEPRECATED")));
        assertEquals("DEPRECATED 는 이름이 최신이어도 고르지 않는다",
                "gemini-2.0-flash", picked);
    }

    @Test
    public void excludesNonVisionFamilies() {
        String picked = VisionModelResolver.selectVertex(Arrays.asList(
                vx("gemini-2.0-flash", "GA"),
                vx("text-embedding-005", "GA"),
                vx("gemma-3-27b", "GA"),
                vx("gemini-embedding-001", "GA")));
        assertEquals("gemini-2.0-flash", picked);
    }

    @Test(expected = NoSuitableModelException.class)
    public void throwsWhenNothingMatchesTheRuleset() {
        VisionModelResolver.selectVertex(Collections.singletonList(
                vx("text-embedding-005", "GA")));
    }

    @Test(expected = NoSuitableModelException.class)
    public void throwsOnEmptyLiveList() {
        VisionModelResolver.selectVertex(Collections.<VertexModel>emptyList());
    }
```

- [ ] **Step 3: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :buildSrc:test`
Expected: 컴파일 실패 — `cannot find symbol: method selectVertex(...)`.

- [ ] **Step 4: `VisionModelResolver` 를 고친다**

`selectGemini` 를 아래 `selectVertex` 로 **교체**하고, `select(...)` 의 시그니처를 바꾼다. `OPENAI_VISION_ALLOWLIST`·`selectOpenAi`·`shortName` 은 그대로 둔다.

`GEMINI_EXCLUDE` 상수를 지우고 그 자리에 넣는다:

```java
    /**
     * Vertex vision-capable name patterns (capability allowlist). Vertex's model list has
     * no capability metadata, so — exactly as with OpenAI — capability is a versioned
     * ruleset intersected with the live list. Acknowledged limitation: a new vision model
     * under an unanticipated name is excluded until this list is updated (RULESET_VERSION).
     */
    static final List<Pattern> VERTEX_VISION_ALLOWLIST = Arrays.asList(
            Pattern.compile("^gemini-[0-9].*$")
    );

    /** Names that match the allowlist prefix but are not vision chat models. */
    private static final Pattern VERTEX_EXCLUDE =
            Pattern.compile("(embedding|aqa|gemma|tts|image|veo|imagen)", Pattern.CASE_INSENSITIVE);

    private static final String DEPRECATED = "DEPRECATED";
```

그리고 `selectGemini` 자리에:

```java
    public static String selectVertex(List<VertexModel> models) {
        String best = models.stream()
                .filter(m -> !DEPRECATED.equalsIgnoreCase(m.launchStage))
                .map(m -> shortName(m.name))
                .filter(n -> VERTEX_VISION_ALLOWLIST.stream().anyMatch(p -> p.matcher(n).matches()))
                .filter(n -> !VERTEX_EXCLUDE.matcher(n).find())
                .max(Comparator.naturalOrder())  // heuristic: lexicographically-newest name
                .orElse(null);
        if (best == null) {
            throw new NoSuitableModelException(
                    "Vertex: live publisher model list has no entry satisfying the vision ruleset"
                            + " (rulesetVersion=" + Constants.RULESET_VERSION
                            + "). The list may be empty/unsuitable, or the ruleset needs updating.");
        }
        return best;
    }

    /** Resolve both providers; throws NoSuitableModelException if either yields nothing. */
    public static String[] select(List<VertexModel> vertex, List<OpenAiModel> openAi) {
        return new String[]{selectVertex(vertex), selectOpenAi(openAi)};
    }
```

기존 `select(List<GeminiModel>, List<OpenAiModel>)` 오버로드는 **지운다**. 클래스 javadoc 의 "Gemini list carries supportedGenerationMethods" 문장도 아래로 갈아끼운다:

```java
 *   - Vertex publisherModels.list carries NO capability metadata (supportedActions is a
 *     console CallToAction, not a generateContent flag), so capability is decided by a
 *     versioned name allowlist. launchStage=DEPRECATED entries are dropped.
```

그리고 `GeminiModel.java` 파일을 삭제한다.

- [ ] **Step 5: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :buildSrc:test --tests '*VisionModelResolverTest*'`
Expected: PASS (Vertex 5건 + 기존 OpenAI 테스트 전부).

- [ ] **Step 6: resolve 태스크를 Vertex 엔드포인트로 돌린다**

`ResolveVisionModelsTask.java` 에서:

`getGeminiApiKey()` 를 `getVertexApiKey()` 로 이름만 바꾸고, `resolve()` 의 키 검사·fetch 부분을 교체한다:

```java
        String vKey = getVertexApiKey().getOrElse("").trim();
        String oKey = getOpenAiApiKey().getOrElse("").trim();
        if (vKey.isEmpty()) {
            throw new GradleException("VERTEX_API_KEY missing in local.properties — cannot resolve vision models. "
                    + "(Missing-key error, distinct from 'no suitable model'.)");
        }
        if (oKey.isEmpty()) {
            throw new GradleException("OPENAI_API_KEY missing in local.properties — cannot resolve vision models. "
                    + "(Missing-key error, distinct from 'no suitable model'.)");
        }

        List<VertexModel> vertex = fetchVertex(vKey);
        List<OpenAiModel> openAi = fetchOpenAi(oKey);

        String geminiModel;
        String openAiModel;
        try {
            String[] r = VisionModelResolver.select(vertex, openAi);
            geminiModel = r[0];
            openAiModel = r[1];
        } catch (NoSuitableModelException e) {
            throw new GradleException("Vision model resolution FAILED: " + e.getMessage(), e);
        }
```

`fetchGemini` 를 통째로 아래로 교체한다(`fetchOpenAi`·`httpGet`·`readAll` 은 그대로):

```java
    /**
     * Vertex Express Mode: the API key goes in the x-goog-api-key header, and the endpoint
     * is region-less / project-less. This is the SAME surface the app calls at runtime, so
     * a model that resolves here is a model that actually answers :generateContent.
     */
    @SuppressWarnings("unchecked")
    private List<VertexModel> fetchVertex(String key) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", key);
        String body = httpGet(
                "https://aiplatform.googleapis.com/v1/publishers/google/models?pageSize=1000",
                headers);
        Object root = new JsonSlurper().parseText(body);
        List<VertexModel> result = new ArrayList<>();
        Object modelsObj = ((Map<String, Object>) root).get("publisherModels");
        if (modelsObj instanceof List) {
            for (Object o : (List<Object>) modelsObj) {
                Map<String, Object> m = (Map<String, Object>) o;
                Object name = m.get("name");
                Object stage = m.get("launchStage");
                result.add(new VertexModel(
                        name == null ? "" : name.toString(),
                        stage == null ? null : stage.toString()));
            }
        }
        return result;
    }
```

`java.util.HashMap` import 는 이미 있다. 클래스 javadoc 의 "Gemini/OpenAI model lists" 를 "Vertex/OpenAI model lists" 로 고친다.

- [ ] **Step 7: 룰셋 버전을 올린다**

`Constants.java`:

```java
    public static final String RULESET_VERSION = "2026-07-vertex";
```

> **이 한 줄이 `./gradlew check` 를 의도적으로 하드 실패시킨다.** `StalenessGuardTask` 는 커밋된 `visionModels.generated.json` 의 `rulesetVersion` 과 코드의 값이 다르면 실패하도록 설계돼 있고, 그게 정확히 지금 상황이다(생성 파일은 아직 `2026-01` 플레이스홀더). Step 9 에서 실제 키로 재해소해야 풀린다.
> **`./gradlew :app:testDebugUnitTest` 와 `:app:assembleDebug` 는 계속 통과한다**(전자는 `check` 라이프사이클이 아니고, 후자는 버전 불일치를 경고로만 낸다). 그러니 Task 2~9 는 이 상태로 정상 진행된다.

- [ ] **Step 8: Gradle 배선·키 이름을 바꾼다**

`android/app/build.gradle` 에서 `GEMINI_API_KEY` 를 쓰는 두 지점을 고친다.

buildConfigField 블록:

```groovy
        buildConfigField "String", "VERTEX_API_KEY",    "\"${runtimeKey('VERTEX_API_KEY')}\""
        buildConfigField "String", "OPENAI_API_KEY",    "\"${runtimeKey('OPENAI_API_KEY')}\""
```

(`GEMINI_API_KEY` buildConfigField 줄은 삭제한다. `GEMINI_VISION_MODEL`·`OPENAI_VISION_MODEL`·`PLACES_API_KEY`·`GEOCODING_API_KEY` 줄은 그대로 둔다.)

**`resolveVisionModels` 태스크 등록부는 `app/build.gradle` 이 아니라 루트 `android/build.gradle` 에 있다**(대략 22~29줄). 거기서 `geminiApiKey.set(...)` 줄을 바꾼다 — Step 6 이 `getGeminiApiKey()` 를 `getVertexApiKey()` 로 이름을 바꾸므로, 이 줄을 안 고치면 `./gradlew resolveVisionModels`(Step 9)가 존재하지 않는 프로퍼티를 세팅하다 실패한다:

```groovy
tasks.register("resolveVisionModels", ResolveVisionModelsTask) {
    group = "traveltrace"
    description = "Resolve current vision model IDs from the live Vertex/OpenAI model lists and rewrite gradle/visionModels.generated.json"
    vertexApiKey.set(localProp("VERTEX_API_KEY"))
    openAiApiKey.set(localProp("OPENAI_API_KEY"))
    rulesetVersion.set(Constants.RULESET_VERSION)
    outputFile.set(rootProject.file("gradle/visionModels.generated.json"))
}
```

같은 파일 위쪽의 주석 `// Hits the live provider model lists...` 도 "live Vertex/OpenAI model lists" 로 맞춘다.

`android/local.properties.example`:

```properties
# Build-time AND runtime: Vertex AI Express Mode API key (x-goog-api-key header).
# `./gradlew resolveVisionModels` uses it to query the live publisher model list, and the
# app uses it at runtime for :generateContent. Same surface, same key — deliberately.
VERTEX_API_KEY=

# Build-time only: needed ONLY when running `./gradlew resolveVisionModels`. (S5 fallback.)
OPENAI_API_KEY=

# Runtime: injected into BuildConfig (Geocoding + Places via REST)
# and the manifest (Maps via meta-data placeholder).
MAPS_API_KEY=
PLACES_API_KEY=
GEOCODING_API_KEY=
```

`android/README.md` 의 A2 절에서 `Gemini GET /v1beta/models` 를 `Vertex GET /v1/publishers/google/models (Express Mode, x-goog-api-key)` 로, A3 절의 키 목록에서 `GEMINI_API_KEY` 를 `VERTEX_API_KEY` 로 고친다. A2 절에 한 문단 추가:

```markdown
Vertex 의 모델 목록에는 능력 메타데이터가 없다(`supportedActions` 는 콘솔 UI 용이라
`generateContent` 지원 여부를 알려주지 않는다). 그래서 vision 판별은 OpenAI 와 동일하게
**버전 관리되는 이름 allowlist**로 하고, Vertex 가 주는 `launchStage` 로 `DEPRECATED` 를
걸러낸다. 조회 표면과 런타임 호출 표면이 같은 Express Mode 엔드포인트이므로, 여기서
확정된 모델은 실제로 `:generateContent` 에 응답하는 모델이다.
```

- [ ] **Step 9: 실제 모델을 재해소한다 (실 키·네트워크 필요)**

Run: `cd android && ./gradlew resolveVisionModels`
Expected: `Resolved vision models -> gemini=<실제 Vertex 모델>, openai=<...> (rulesetVersion=2026-07-vertex)` 가 찍히고 `gradle/visionModels.generated.json` 이 갱신된다.

> **키가 없으면 여기서 멈춘다.** `VERTEX_API_KEY missing in local.properties` 로 실패하는 것이 **정상 동작**이다(플레이스홀더로 손편집 금지 — `_note` 가 금지하고 있고, 손편집하면 A2 의 존재 이유가 사라진다). 키를 확보할 때까지 이 Step 은 미완으로 두고 Task 2 로 진행하되, **Task 9 종료 전에는 반드시 통과시켜야 한다** — 안 그러면 앱이 검증된 적 없는 모델 ID 로 나간다.

- [ ] **Step 10: 검증하고 커밋한다**

Run: `cd android && ./gradlew :buildSrc:test && ./gradlew :app:assembleDebug`
Expected: 둘 다 BUILD SUCCESSFUL. (Step 9 를 못 돌렸다면 `assembleDebug` 는 룰셋 불일치 **경고**를 내지만 성공한다.)

```bash
git add android/buildSrc android/build.gradle android/app/build.gradle android/local.properties.example android/README.md android/gradle/visionModels.generated.json
git commit -m "build: resolve vision models against Vertex AI Express instead of the Gemini Developer API

조회 표면과 런타임 호출 표면을 일치시킨다. Developer API 목록에 있어도 Vertex express
에선 404 인 모델이 실재하므로, Vertex 에서 확정한 모델만 앱에 주입한다. Vertex 목록엔
능력 메타데이터가 없어 vision 판별은 OpenAI 와 동일한 버전 관리 allowlist 로 하고,
Vertex 가 주는 launchStage 로 DEPRECATED 를 제외한다."
```

---

## Task 2: 업로드 준비 — 다운스케일 · JPEG 재인코딩 · 콘텐츠 해시

프라이버시 계약의 ② 단계. **모든 원본을 포맷 무관 JPEG 로 재인코딩하는 것**이 EXIF 제거의 유일한 보장 수단이다 — `ExifInterface` 의 스트립은 JPEG 전용이라 HEIC/RAW 에 동작하지 않으므로 의존하지 않는다(PRD §5).

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/analysis/UploadPreparer.java`
- Test: `android/app/src/test/java/com/traveltrace/app/analysis/UploadPreparerTest.java`

**Interfaces:**
- Consumes: Task 1 의 산출물 없음(독립).
- Produces:
  - `UploadPreparer.Prepared { public final byte[] jpeg; public final String contentHash; }`
  - `UploadPreparer#prepare(android.net.Uri contentUri) throws java.io.IOException` → `Prepared`
  - `UploadPreparer.MAX_EDGE_PX = 1024`, `UploadPreparer.JPEG_QUALITY = 80`
  - 생성자: `@Inject UploadPreparer(@ApplicationContext Context context)` — Hilt 가 모듈 없이 주입한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/analysis/UploadPreparerTest.java`:

```java
package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 프라이버시 계약(PRD §5)의 회귀 방지선. 업로드 산출물에 GPS/시각 EXIF 가 남으면
 * 사진의 실제 촬영 위치가 외부 API 로 새어나간다 — 이 앱이 AI 에 사진을 보내면서도
 * 위치는 로컬에만 둔다고 말할 수 있는 근거가 통째로 무너지는 지점이다.
 *
 * <p>NATIVE 그래픽스 모드가 필수다. 기본(LEGACY) 모드에서는 Robolectric 이
 * BitmapFactory/Bitmap.compress 를 가짜로 대체하므로 "재인코딩이 EXIF 를 지운다"를
 * 검증할 수 없다 — 실제 Skia 인코더가 돌아야 의미 있는 테스트다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UploadPreparerTest {

    private Context ctx;
    private UploadPreparer preparer;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        preparer = new UploadPreparer(ctx);
    }

    /** GPS·촬영시각 EXIF 를 실제로 박은 JPEG 를 만들어 ContentResolver 에 등록한다. */
    private Uri seedJpegWithExif(int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF3366CC);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, raw);

        // ExifInterface 는 파일 경로로 열어야 저장(saveAttributes)이 가능하다.
        File file = File.createTempFile("seed", ".jpg", ctx.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(raw.toByteArray());
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,2999/100");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,17/1,4000/100");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 09:30:00");
        exif.saveAttributes();

        byte[] withExif = readFile(file);
        Uri uri = Uri.parse("content://media/external/images/media/42");
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new ByteArrayInputStream(withExif));
        return uri;
    }

    private static byte[] readFile(File file) throws Exception {
        byte[] buf = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return buf;
    }

    @Test
    public void strippedOutputHasNoGpsOrTimestampExif() throws Exception {
        Uri uri = seedJpegWithExif(2000, 1500);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        ExifInterface out = new ExifInterface(new ByteArrayInputStream(prepared.jpeg));
        assertNull("업로드본에 GPS 위도가 남으면 안 된다", out.getLatLong());
        assertNull("업로드본에 촬영 시각이 남으면 안 된다",
                out.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
    }

    @Test
    public void downscalesLongEdgeToTheCap() throws Exception {
        Uri uri = seedJpegWithExif(2000, 1500);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.length, bounds);
        assertEquals("장변이 상한에 맞춰진다", UploadPreparer.MAX_EDGE_PX, bounds.outWidth);
        assertEquals("종횡비가 유지된다", 768, bounds.outHeight);
    }

    @Test
    public void doesNotUpscaleSmallOriginals() throws Exception {
        Uri uri = seedJpegWithExif(320, 240);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.length, bounds);
        assertEquals("작은 원본을 늘리면 인식률은 그대로인데 업로드 비용만 커진다",
                320, bounds.outWidth);
        assertEquals(240, bounds.outHeight);
    }

    @Test
    public void outputIsAlwaysJpegRegardlessOfInput() throws Exception {
        Uri uri = seedJpegWithExif(800, 600);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        // SOI 마커 0xFFD8 — 포맷 분기 없이 항상 JPEG 로 재인코딩된다는 계약의 증거.
        assertEquals((byte) 0xFF, prepared.jpeg[0]);
        assertEquals((byte) 0xD8, prepared.jpeg[1]);
    }

    @Test
    public void contentHashIsStableAcrossCallsAndDiffersByContent() throws Exception {
        Uri a1 = seedJpegWithExif(400, 300);
        String first = preparer.prepare(a1).contentHash;
        Uri a2 = seedJpegWithExif(400, 300);
        String second = preparer.prepare(a2).contentHash;
        assertEquals("같은 바이트는 같은 해시 — S4 캐시 키의 전제", first, second);

        Uri other = seedJpegWithExif(401, 300);
        assertNotEquals("다른 사진은 다른 해시", first, preparer.prepare(other).contentHash);
    }

    @Test
    public void hashIsSha256Hex() throws Exception {
        String hash = preparer.prepare(seedJpegWithExif(200, 200)).contentHash;
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*UploadPreparerTest*'`
Expected: 컴파일 실패 — `package com.traveltrace.app.analysis does not exist`.

- [ ] **Step 3: `UploadPreparer` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/analysis/UploadPreparer.java`:

```java
package com.traveltrace.app.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 전송 파이프라인 ② 단계 (PRD §5): 원본 → 다운스케일 JPEG + 콘텐츠 해시.
 *
 * <p><b>EXIF 제거는 재인코딩이 보장한다.</b> {@code ExifInterface} 의 스트립은 JPEG
 * 전용이라 HEIC/RAW 엔 동작하지 않으므로 별도 스트립 단계를 두지 않는다. 대신 입력
 * 포맷과 무관하게 <em>항상</em> 픽셀로 디코드해 JPEG 로 다시 인코딩한다 — 산출물이
 * 원본 메타데이터를 물려받을 경로 자체가 없다. 포맷 분기가 없다는 것이 계약이다.
 *
 * <p><b>원본이 아닌 일반 URI 로 연다.</b> {@code setRequireOriginal} 은 여기서 쓰지
 * 않는다. ① 단계({@link com.traveltrace.app.data.exif.ExifExtractor})가 이미 원본에서
 * GPS 를 읽어갔고, 업로드 경로엔 위치가 필요 없다. scoped storage 가 일반 URI 의 위치
 * EXIF 를 이미 가려주므로 재인코딩과 합쳐 방어가 2겹이 된다.
 *
 * <p><b>스트림은 1회만 읽는다</b>(PRD §4.7 해시 시점). 바이트를 메모리에 한 번 받아
 * 그 배열로 해시와 디코드를 모두 해결한다 — 같은 사진을 두 번 읽지 않는다. 배열 하나가
 * 통째로 메모리에 올라오지만 S3 는 사진을 <em>직렬로</em> 1장씩 처리하므로 동시에 살아
 * 있는 원본은 항상 1개다(동시 4건은 S4 소관이며, 그때 이 전제를 다시 봐야 한다).
 */
@Singleton
public class UploadPreparer {

    /** 업로드 장변 상한. 더 키워도 랜드마크 인식률 이득이 작고 업로드 비용만 는다. */
    public static final int MAX_EDGE_PX = 1024;

    /** JPEG 품질. 80 이하로 내리면 간판·표지판 인식이 눈에 띄게 나빠진다. */
    public static final int JPEG_QUALITY = 80;

    private final Context context;

    @Inject
    public UploadPreparer(@ApplicationContext Context context) {
        this.context = context;
    }

    /** 업로드 바이트와 콘텐츠 해시. 해시는 <em>원본</em> 바이트 기준이다. */
    public static final class Prepared {
        public final byte[] jpeg;
        public final String contentHash;

        Prepared(byte[] jpeg, String contentHash) {
            this.jpeg = jpeg;
            this.contentHash = contentHash;
        }
    }

    public Prepared prepare(Uri contentUri) throws IOException {
        byte[] original = readAll(contentUri);
        String hash = sha256Hex(original);

        Bitmap decoded = decodeDownscaled(original);
        if (decoded == null) {
            throw new IOException("이미지를 디코드할 수 없다: " + contentUri);
        }
        Bitmap scaled = scaleToCap(decoded);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            return new Prepared(out.toByteArray(), hash);
        } finally {
            if (scaled != decoded) {
                scaled.recycle();
            }
            decoded.recycle();
        }
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("스트림을 열 수 없다: " + uri);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * inSampleSize 로 <em>디코드 단계에서</em> 이미 줄여 받는다. 4000x3000 RAW 를
     * 원본 해상도로 올렸다가 줄이면 48MB 비트맵이 잠깐 뜨고 저사양 기기에서 OOM 이 난다.
     */
    private static Bitmap decodeDownscaled(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    /** 장변이 상한 아래로 내려가지 않는 선까지만 2의 거듭제곱으로 줄인다. */
    static int sampleSizeFor(int width, int height) {
        int longEdge = Math.max(width, height);
        int sample = 1;
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2;
        }
        return sample;
    }

    /** inSampleSize 는 2의 거듭제곱 단위라 남는 오차를 여기서 정확히 맞춘다. */
    private static Bitmap scaleToCap(Bitmap source) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= MAX_EDGE_PX) {
            return source;  // 확대는 하지 않는다.
        }
        float ratio = (float) MAX_EDGE_PX / longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * ratio));
        int height = Math.max(1, Math.round(source.getHeight() * ratio));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 은 모든 안드로이드 런타임이 제공한다.
            throw new IllegalStateException(impossible);
        }
    }
}
```

- [ ] **Step 4: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*UploadPreparerTest*'`
Expected: PASS 6건.

> `downscalesLongEdgeToTheCap` 이 실패하면 `sampleSizeFor` 를 먼저 의심한다. 2000x1500 → sample=1(2000/2=1000 은 1024 미만이라 더 안 줄인다) → `scaleToCap` 이 1024x768 로 맞춘다.

- [ ] **Step 5: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/analysis/UploadPreparer.java \
        android/app/src/test/java/com/traveltrace/app/analysis/UploadPreparerTest.java
git commit -m "feat: downscale + re-encode photos to EXIF-free JPEG before upload

PRD §5 파이프라인 ② 단계. 포맷 분기 없이 항상 픽셀로 디코드→JPEG 재인코딩하므로
HEIC/RAW 도 EXIF 가 남을 경로가 없다(ExifInterface 스트립은 JPEG 전용이라 안 쓴다).
콘텐츠 해시는 같은 스트림 1회 읽기에서 함께 뽑는다."
```

> **기기 검증(자동화 불가, 수동):** JVM 테스트로는 HEIC/RAW 입력을 합성할 수 없다. S3 완료 전에 실기기에서 HEIC 사진(아이폰 촬영본)과 RAW 1장씩으로 분석을 돌리고, 로그로 산출물이 JPEG SOI 로 시작하는지 1회 확인한다. 디코더 미지원 기기에서는 `prepare` 가 `IOException` 을 던지고 해당 사진이 UNKNOWN 으로 떨어지는 것이 의도된 동작이다(Task 7).

---

## Task 3: Vertex 요청 조립 · 응답 파싱 (순수 함수)

HTTP 를 타지 않는 두 순수 클래스로 나눈다. 이렇게 하면 "스키마를 실제로 보냈는가"와 "스키마 위반 응답을 인식 실패로 다루는가"를 **MockWebServer 없이** 검증할 수 있다 — 이 저장소는 모킹 라이브러리를 쓰지 않는다는 규약이 있고, 새 테스트 의존성을 추가하지 않는 편이 그 규약에 맞다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/vision/VertexRequestBuilder.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/vision/VertexResponseParser.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/vision/VertexRequestBuilderTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/vision/VertexResponseParserTest.java`

**Interfaces:**
- Consumes: `com.traveltrace.app.core.model.RecognitionResult` (기존, 4-arg 생성자 `(String landmarkName, String city, String country, double confidence)`).
- Produces:
  - `VertexRequestBuilder.build(byte[] jpeg)` → `com.google.gson.JsonObject`
  - `VertexRequestBuilder.MIN_CONFIDENCE` 는 여기 두지 **않는다**(Task 5 의 `GeocodeRouter` 소유).
  - `VertexResponseParser.parse(JsonObject response)` → `RecognitionResult` (예외를 던지지 않는다)
  - `VertexResponseParser.UNRECOGNIZED` → `RecognitionResult(null, null, null, 0d)` 상수

- [ ] **Step 1: 요청 조립 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/data/vision/VertexRequestBuilderTest.java`:

```java
package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;

/**
 * 요청 바디를 "실제 나가는 모양" 그대로 고정한다. responseSchema 를 빼먹어도 모델은
 * 그럴듯한 JSON 을 돌려주므로 통합 테스트로는 회귀가 안 잡힌다 — 스키마가 와이어에
 * 실렸는지는 여기서만 증명된다(PRD §4.3 "네이티브 스키마 강제").
 */
@RunWith(RobolectricTestRunner.class)
public class VertexRequestBuilderTest {

    private static final byte[] JPEG = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

    private static JsonObject firstUserPart(JsonObject body, int index) {
        return body.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(index).getAsJsonObject();
    }

    @Test
    public void everyContentEntryCarriesARole() {
        JsonObject body = VertexRequestBuilder.build(JPEG);
        JsonArray contents = body.getAsJsonArray("contents");
        for (int i = 0; i < contents.size(); i++) {
            assertTrue("Vertex 는 role 을 필수로 요구한다 — Developer API 와 달리 생략하면 400",
                    contents.get(i).getAsJsonObject().has("role"));
        }
    }

    @Test
    public void sendsImageAsBase64InlineData() {
        JsonObject inline = firstUserPart(VertexRequestBuilder.build(JPEG), 0)
                .getAsJsonObject("inlineData");
        assertEquals("image/jpeg", inline.get("mimeType").getAsString());
        assertEquals(android.util.Base64.encodeToString(JPEG, android.util.Base64.NO_WRAP),
                inline.get("data").getAsString());
    }

    @Test
    public void forcesJsonMimeTypeAndResponseSchema() {
        JsonObject config = VertexRequestBuilder.build(JPEG).getAsJsonObject("generationConfig");
        assertEquals("application/json", config.get("responseMimeType").getAsString());
        assertTrue("responseSchema 없이 나가면 스키마 강제가 아니라 프롬프트 유도일 뿐이다",
                config.has("responseSchema"));
    }

    @Test
    public void pinsTemperatureToZero() {
        JsonObject config = VertexRequestBuilder.build(JPEG).getAsJsonObject("generationConfig");
        assertTrue("temperature 0 이 누락되면 같은 사진이 호출마다 다른 장소로 인식된다",
                config.has("temperature"));
        assertEquals(0d, config.get("temperature").getAsDouble(), 0d);
    }

    @Test
    public void schemaHasNoCoordinateFields() {
        JsonObject props = VertexRequestBuilder.build(JPEG)
                .getAsJsonObject("generationConfig")
                .getAsJsonObject("responseSchema")
                .getAsJsonObject("properties");
        assertFalse("AI 가 좌표를 만들 통로를 스키마 차원에서 막는다(PRD §4.3)", props.has("lat"));
        assertFalse(props.has("lng"));
        assertFalse(props.has("latitude"));
        assertFalse(props.has("longitude"));
        assertEquals(4, props.size());
        assertTrue(props.has("landmarkName"));
        assertTrue(props.has("city"));
        assertTrue(props.has("country"));
        assertTrue(props.has("confidence"));
    }

    @Test
    public void onlyConfidenceIsRequired() {
        JsonArray required = VertexRequestBuilder.build(JPEG)
                .getAsJsonObject("generationConfig")
                .getAsJsonObject("responseSchema")
                .getAsJsonArray("required");
        assertEquals("인식 실패는 정상 결과다 — 이름을 필수로 만들면 모델이 지어낸다",
                1, required.size());
        assertEquals("confidence", required.get(0).getAsString());
    }

    @Test
    public void includesSystemInstruction() {
        assertTrue(VertexRequestBuilder.build(JPEG).has("systemInstruction"));
    }
}
```

- [ ] **Step 2: 응답 파싱 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/data/vision/VertexResponseParserTest.java`:

```java
package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 스키마 위반·빈 응답은 <em>예외가 아니라 인식 실패</em>여야 한다(plan/08 DoD).
 * 실내·음식 사진에서 인식이 안 되는 건 오류가 아니라 정상 경로이며, 여기서 예외를
 * 던지면 그 사진 한 장이 배치 전체를 중단시킨다.
 */
@RunWith(RobolectricTestRunner.class)
public class VertexResponseParserTest {

    private static JsonObject wrap(String modelText) {
        JsonObject part = new JsonObject();
        part.addProperty("text", modelText);
        JsonObject content = new JsonObject();
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        parts.add(part);
        content.add("parts", parts);
        JsonObject candidate = new JsonObject();
        candidate.add("content", content);
        com.google.gson.JsonArray candidates = new com.google.gson.JsonArray();
        candidates.add(candidate);
        JsonObject root = new JsonObject();
        root.add("candidates", candidates);
        return root;
    }

    @Test
    public void parsesAFullyPopulatedResult() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":\"에펠탑\",\"city\":\"파리\",\"country\":\"프랑스\",\"confidence\":0.92}"));
        assertEquals("에펠탑", r.landmarkName);
        assertEquals("파리", r.city);
        assertEquals("프랑스", r.country);
        assertEquals(0.92d, r.confidence, 1e-9);
    }

    @Test
    public void nullNamesSurviveAsNull() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "{\"landmarkName\":null,\"city\":\"파리\",\"country\":null,\"confidence\":0.5}"));
        assertNull(r.landmarkName);
        assertEquals("파리", r.city);
        assertNull(r.country);
    }

    @Test
    public void stripsCodeFencesTheModelSometimesAdds() {
        RecognitionResult r = VertexResponseParser.parse(wrap(
                "```json\n{\"city\":\"파리\",\"confidence\":0.7}\n```"));
        assertEquals("파리", r.city);
        assertEquals(0.7d, r.confidence, 1e-9);
    }

    @Test
    public void malformedJsonBecomesUnrecognizedNotAnException() {
        RecognitionResult r = VertexResponseParser.parse(wrap("{not json at all"));
        assertEquals(0d, r.confidence, 0d);
        assertNull(r.landmarkName);
    }

    @Test
    public void missingConfidenceBecomesUnrecognized() {
        RecognitionResult r = VertexResponseParser.parse(wrap("{\"city\":\"파리\"}"));
        assertEquals("스키마 위반은 인식 실패다 — 이름만 믿고 confidence 를 지어내지 않는다",
                0d, r.confidence, 0d);
    }

    @Test
    public void emptyCandidatesBecomesUnrecognized() {
        JsonObject root = JsonParser.parseString("{\"candidates\":[]}").getAsJsonObject();
        assertEquals(0d, VertexResponseParser.parse(root).confidence, 0d);
    }

    @Test
    public void safetyBlockedResponseBecomesUnrecognized() {
        JsonObject root = JsonParser.parseString(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").getAsJsonObject();
        assertEquals(0d, VertexResponseParser.parse(root).confidence, 0d);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*Vertex*'`
Expected: 컴파일 실패 — `cannot find symbol: class VertexRequestBuilder`.

- [ ] **Step 4: `VertexRequestBuilder` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/data/vision/VertexRequestBuilder.java`:

```java
package com.traveltrace.app.data.vision;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Vertex AI {@code :generateContent} 요청 바디 조립 (PRD §4.3).
 *
 * <p><b>스키마는 프롬프트가 아니라 {@code responseSchema} 로 강제한다.</b> 프롬프트로
 * "JSON 으로 답해"라고만 하면 모델이 코드펜스·설명문을 붙이거나 필드명을 바꾼다.
 *
 * <p><b>Vertex 는 모든 content 항목에 {@code role} 을 요구한다</b> — Developer API 는
 * 생략을 허용했지만 Vertex 는 400 을 낸다. 두 API 의 실질적 차이가 이것과 호스트뿐이라
 * 빠뜨리기 쉽다.
 *
 * <p>좌표 필드는 스키마에 <em>없다</em>. AI 가 lat/lng 를 만들지 못하게 막는 것이
 * {@link com.traveltrace.app.core.model.RecognitionResult} 에 좌표가 없는 이유와 같고,
 * 여기가 그 계약의 바깥쪽 절반이다(§4.2-2).
 */
public final class VertexRequestBuilder {

    private static final String MIME_JPEG = "image/jpeg";

    /**
     * 영어 프롬프트다 — 한국어 리터럴 금지 규약은 <em>사용자 대면</em> 문자열 대상이고,
     * 이건 모델에게 가는 문자열이라 화면에 뜨지 않는다. 한국어 지명을 돌려받기 위해
     * 출력 언어만 명시적으로 지정한다.
     */
    private static final String SYSTEM_INSTRUCTION =
            "You identify where a travel photo was taken. Return ONLY the NAME of the place — "
                    + "never coordinates. If a specific landmark or point of interest is clearly "
                    + "visible, put it in landmarkName. Fill city and country whenever you can infer "
                    + "them. If the photo shows a generic interior, food, a close-up, or anything "
                    + "without a locatable cue, leave the names null and set confidence to 0 — "
                    + "failing to recognise is a correct answer and is far better than guessing. "
                    + "confidence is your calibrated probability (0.0-1.0) that the named place is "
                    + "correct. Write place names in Korean.";

    private VertexRequestBuilder() {}

    public static JsonObject build(byte[] jpeg) {
        JsonObject inlineData = new JsonObject();
        inlineData.addProperty("mimeType", MIME_JPEG);
        inlineData.addProperty("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));

        JsonObject imagePart = new JsonObject();
        imagePart.add("inlineData", inlineData);

        JsonArray parts = new JsonArray();
        parts.add(imagePart);

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");   // Vertex 필수
        userContent.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());
        // 같은 사진이 호출마다 다른 장소로 인식되면 캐시(S4)와 사용자 신뢰가 함께 무너진다.
        generationConfig.addProperty("temperature", 0);

        JsonObject systemParts = new JsonObject();
        systemParts.addProperty("text", SYSTEM_INSTRUCTION);
        JsonArray systemPartArray = new JsonArray();
        systemPartArray.add(systemParts);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemPartArray);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        body.add("systemInstruction", systemInstruction);
        return body;
    }

    /** Gemini 의 OpenAPI 서브셋 — 타입명은 대문자다. */
    static JsonObject responseSchema() {
        JsonObject properties = new JsonObject();
        properties.add("landmarkName", nullableString());
        properties.add("city", nullableString());
        properties.add("country", nullableString());

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "NUMBER");
        properties.add("confidence", confidence);

        // confidence 만 required. 이름을 required 로 만들면 모델이 빈 문자열을 지어내
        // "인식 실패"라는 정상 결과를 표현할 방법이 사라진다.
        JsonArray required = new JsonArray();
        required.add("confidence");

        JsonArray ordering = new JsonArray();
        ordering.add("landmarkName");
        ordering.add("city");
        ordering.add("country");
        ordering.add("confidence");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.add("propertyOrdering", ordering);
        return schema;
    }

    private static JsonObject nullableString() {
        JsonObject field = new JsonObject();
        field.addProperty("type", "STRING");
        field.addProperty("nullable", true);
        return field;
    }
}
```

- [ ] **Step 5: `VertexResponseParser` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/data/vision/VertexResponseParser.java`:

```java
package com.traveltrace.app.data.vision;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.RecognitionResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vertex 응답 → {@link RecognitionResult}.
 *
 * <p><b>절대 던지지 않는다.</b> 스키마 위반·깨진 JSON·안전필터 차단·빈 candidates 는
 * 전부 {@link #UNRECOGNIZED}(confidence 0) 로 수렴한다 — plan/08 DoD 가 요구하는
 * "스키마 위반은 파싱 에러가 아니라 인식 실패" 계약이다. 실내·음식 사진의 인식 실패는
 * 정상 경로이므로(PRD §8), 여기서 예외를 던지면 사진 1장이 배치를 중단시킨다.
 */
public final class VertexResponseParser {

    /** 인식 실패. 이름 없음 + confidence 0 → 상위에서 UNKNOWN 으로 분류된다. */
    public static final RecognitionResult UNRECOGNIZED =
            new RecognitionResult(null, null, null, 0d);

    private static final Pattern CODE_FENCE =
            Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$");

    private VertexResponseParser() {}

    public static RecognitionResult parse(JsonObject response) {
        String text = firstText(response);
        if (text == null) {
            return UNRECOGNIZED;
        }
        JsonObject payload = parseObject(stripCodeFences(text));
        if (payload == null) {
            return UNRECOGNIZED;
        }
        // confidence 부재 = 스키마 위반. 이름만 믿고 임의의 신뢰도를 만들어내면
        // 저신뢰 강등(NAME_ONLY)이 통째로 무력해진다.
        if (!payload.has("confidence") || !payload.get("confidence").isJsonPrimitive()) {
            return UNRECOGNIZED;
        }
        double confidence;
        try {
            confidence = payload.get("confidence").getAsDouble();
        } catch (NumberFormatException notANumber) {
            return UNRECOGNIZED;
        }
        return new RecognitionResult(
                optionalString(payload, "landmarkName"),
                optionalString(payload, "city"),
                optionalString(payload, "country"),
                confidence);
    }

    @Nullable
    private static String firstText(@Nullable JsonObject response) {
        if (response == null || !response.has("candidates")) {
            return null;   // 안전필터 차단 시 candidates 자체가 없다.
        }
        JsonElement candidatesElement = response.get("candidates");
        if (!candidatesElement.isJsonArray()) {
            return null;
        }
        JsonArray candidates = candidatesElement.getAsJsonArray();
        if (candidates.size() == 0) {
            return null;
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) {
            return null;
        }
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts.size() == 0) {
            return null;
        }
        JsonObject part = parts.get(0).getAsJsonObject();
        return part.has("text") ? part.get("text").getAsString() : null;
    }

    /** responseSchema 를 써도 모델이 가끔 펜스를 붙인다 — 방어적으로 벗긴다. */
    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        Matcher fenced = CODE_FENCE.matcher(trimmed);
        return fenced.matches() ? fenced.group(1).trim() : trimmed;
    }

    @Nullable
    private static JsonObject parseObject(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Nullable
    private static String optionalString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String value = object.get(key).getAsString();
        return value.trim().isEmpty() ? null : value;
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*VertexRequestBuilderTest*' --tests '*VertexResponseParserTest*'`
Expected: PASS 14건.

- [ ] **Step 7: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/data/vision \
        android/app/src/test/java/com/traveltrace/app/data/vision
git commit -m "feat: build Vertex generateContent requests and parse them defensively

responseSchema 로 스키마를 강제하고(프롬프트 유도 아님), 스키마에 좌표 필드를 두지 않아
AI 가 lat/lng 를 만들 통로를 막는다. 파서는 절대 던지지 않는다 — 위반/차단/깨진 JSON 은
모두 confidence 0 인식 실패로 수렴한다. 인식 실패는 오류가 아니라 정상 경로다."
```

---

## Task 4: `VertexGeminiProvider` + 네트워크 배선

이 앱 최초의 Retrofit 인스턴스를 만든다. `VisionProvider` 는 **블로킹·throwing** 계약이므로 `Call.execute()` 를 그대로 쓴다 — 호출자(Task 7 파이프라인)가 이미 `AppExecutors.io()` 위에 있다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/vision/VertexApi.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/vision/VertexGeminiProvider.java`
- Create: `android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java`
- Test: `android/app/src/test/java/com/traveltrace/app/data/vision/VertexGeminiProviderTest.java`

**Interfaces:**
- Consumes: `VertexRequestBuilder.build(byte[])`, `VertexResponseParser.parse(JsonObject)` (Task 3). `BuildConfig.GEMINI_VISION_MODEL`, `BuildConfig.VERTEX_API_KEY` (Task 1).
- Produces:
  - `VertexApi#generateContent(String model, String apiKey, JsonObject body)` → `retrofit2.Call<JsonObject>`
  - `VertexGeminiProvider implements VisionProvider`, 생성자 `@Inject VertexGeminiProvider(VertexApi api)`
  - `NetworkModule` 이 제공: `Gson`, `OkHttpClient`, `VertexApi`, `PlacesTextSearchApi`, `GeocodingApi` — 뒤 둘은 Task 6 에서 채운다. **이 태스크에서는 `VertexApi` 만 제공하고, Task 6 이 나머지를 추가한다.**

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`VisionProvider` 의 계약(HTTP 실패는 예외, 200 은 파싱)만 검증한다. `VertexApi` 를 손으로 만든 페이크로 대체한다 — 이 저장소의 규약(모킹 라이브러리 없음, 테스트 내부 package-private 페이크)에 맞춘다.

Create `android/app/src/test/java/com/traveltrace/app/data/vision/VertexGeminiProviderTest.java`:

```java
package com.traveltrace.app.data.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@RunWith(RobolectricTestRunner.class)
public class VertexGeminiProviderTest {

    private static final byte[] JPEG = "jpeg".getBytes(StandardCharsets.UTF_8);

    @Test
    public void parsesASuccessfulResponse() throws Exception {
        RecordingApi api = RecordingApi.succeeding(
                "{\"landmarkName\":\"에펠탑\",\"city\":\"파리\",\"country\":\"프랑스\",\"confidence\":0.9}");

        RecognitionResult r = new VertexGeminiProvider(api).recognize(JPEG);

        assertEquals("에펠탑", r.landmarkName);
        assertEquals(0.9d, r.confidence, 1e-9);
    }

    @Test
    public void sendsTheResolvedModelIdAndKey() throws Exception {
        RecordingApi api = RecordingApi.succeeding("{\"confidence\":0}");

        new VertexGeminiProvider(api).recognize(JPEG);

        assertNotNull("모델 ID 가 비면 Vertex 가 404 를 낸다", api.model);
        assertTrue("모델 ID 는 BuildConfig 에서 온다 — 하드코딩하면 modelIdGuard 가 빌드를 깬다",
                api.model.length() > 0);
        assertNotNull(api.apiKey);
        assertTrue("요청 바디는 VertexRequestBuilder 산출물이어야 한다",
                api.body.has("generationConfig"));
    }

    @Test
    public void httpFailureThrowsSoTheCallerCanCountIt() {
        RecordingApi api = RecordingApi.failingWith(429);
        try {
            new VertexGeminiProvider(api).recognize(JPEG);
            fail("HTTP 실패는 인식 실패와 구분돼야 한다 — S4 재시도/백오프가 이 예외에 붙는다");
        } catch (Exception expected) {
            assertTrue(expected instanceof IOException);
            assertTrue(expected.getMessage().contains("429"));
        }
    }

    /** 테스트 안에서만 쓰는 손수 만든 페이크. 이 저장소는 모킹 라이브러리를 쓰지 않는다. */
    private static final class RecordingApi implements VertexApi {
        String model;
        String apiKey;
        JsonObject body;
        private final JsonObject success;
        private final int errorCode;

        private RecordingApi(JsonObject success, int errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }

        static RecordingApi succeeding(String modelJson) {
            JsonObject part = new JsonObject();
            part.addProperty("text", modelJson);
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject content = new JsonObject();
            content.add("parts", parts);
            JsonObject candidate = new JsonObject();
            candidate.add("content", content);
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            JsonObject root = new JsonObject();
            root.add("candidates", candidates);
            return new RecordingApi(root, 0);
        }

        static RecordingApi failingWith(int code) {
            return new RecordingApi(null, code);
        }

        @Override
        public Call<JsonObject> generateContent(String model, String apiKey, JsonObject body) {
            this.model = model;
            this.apiKey = apiKey;
            this.body = body;
            return new StubCall(errorCode == 0
                    ? Response.success(success)
                    : Response.error(errorCode, ResponseBody.create(
                            MediaType.parse("application/json"), "{\"error\":\"nope\"}")));
        }
    }

    /** execute() 만 의미 있는 최소 Call. 나머지는 이 코드 경로에서 호출되지 않는다. */
    private static final class StubCall implements Call<JsonObject> {
        private final Response<JsonObject> response;

        StubCall(Response<JsonObject> response) {
            this.response = response;
        }

        @Override public Response<JsonObject> execute() { return response; }
        @Override public void enqueue(Callback<JsonObject> callback) { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return true; }
        @Override public void cancel() {}
        @Override public boolean isCanceled() { return false; }
        @Override public Call<JsonObject> clone() { return new StubCall(response); }
        @Override public okhttp3.Request request() { throw new UnsupportedOperationException(); }
        @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*VertexGeminiProviderTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class VertexApi`.

- [ ] **Step 3: `VertexApi` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/data/vision/VertexApi.java`:

```java
package com.traveltrace.app.data.vision;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Vertex AI Express Mode. 프로젝트 ID·리전·서비스 계정이 경로에 없는 것이 Express 의
 * 정의다 — API 키 헤더 하나로 인증한다. base URL 은 {@code https://aiplatform.googleapis.com/}
 * (NetworkModule).
 *
 * <p>경로의 {@code :generateContent} 는 리터럴이다. Retrofit 은 {@code {model}} 만
 * 치환하고 콜론 뒤는 그대로 둔다.
 */
public interface VertexApi {

    @POST("v1/publishers/google/models/{model}:generateContent")
    Call<JsonObject> generateContent(@Path("model") String model,
                                     @Header("x-goog-api-key") String apiKey,
                                     @Body JsonObject body);
}
```

- [ ] **Step 4: `VertexGeminiProvider` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/data/vision/VertexGeminiProvider.java`:

```java
package com.traveltrace.app.data.vision;

import com.google.gson.JsonObject;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Response;

/**
 * {@link VisionProvider} 의 Vertex AI 구현 (PRD §4.3, S3 단일 프로바이더).
 *
 * <p>블로킹이다 — 인터페이스가 그렇게 정의돼 있고, 호출자는 이미 백그라운드
 * 스레드({@code AppExecutors.io()}) 위에 있다.
 *
 * <p><b>두 종류의 실패를 구분한다.</b> HTTP·네트워크 실패는 {@link IOException} 으로
 * <em>던지고</em>, 모델이 "모르겠다"고 답한 것은 confidence 0 인 정상 결과로 <em>돌려준다</em>.
 * 이 구분이 S4 의 재시도·백오프가 붙을 자리를 만든다 — 실내 사진을 재시도해봐야
 * 돈만 쓴다.
 *
 * <p>모델 ID 는 {@link BuildConfig#GEMINI_VISION_MODEL}(빌드 시점 Vertex 에서 확정)에서
 * 읽는다. 여기에 문자열 리터럴로 쓰면 {@code modelIdGuard} 가 빌드를 깬다 — 의도된 방어다.
 */
@Singleton
public class VertexGeminiProvider implements VisionProvider {

    private final VertexApi api;

    @Inject
    public VertexGeminiProvider(VertexApi api) {
        this.api = api;
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) throws Exception {
        JsonObject body = VertexRequestBuilder.build(imageJpeg);
        Response<JsonObject> response = api.generateContent(
                BuildConfig.GEMINI_VISION_MODEL, BuildConfig.VERTEX_API_KEY, body).execute();

        if (!response.isSuccessful()) {
            // 404 는 십중팔구 죽은/미노출 모델 ID 다 — ./gradlew resolveVisionModels 를 다시 돌려야 한다.
            throw new IOException("Vertex generateContent HTTP " + response.code());
        }
        JsonObject payload = response.body();
        if (payload == null) {
            throw new IOException("Vertex generateContent returned an empty body");
        }
        return VertexResponseParser.parse(payload);
    }
}
```

- [ ] **Step 5: `NetworkModule` 을 만든다**

Create `android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java`:

```java
package com.traveltrace.app.di;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.data.vision.VertexApi;

import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 이 앱 최초의 Retrofit 배선. 호스트가 셋(Vertex·Places·Geocoding)이라 Retrofit 인스턴스도
 * 셋이지만, {@link OkHttpClient} 와 {@link Gson} 은 하나를 공유한다 — 커넥션 풀·스레드풀을
 * 세 벌 만들 이유가 없다.
 *
 * <p><b>타임아웃은 기본값을 쓰지 않는다.</b> OkHttp 기본 read 타임아웃(10s)은 큰 이미지의
 * Vertex 추론엔 짧고, 무한대는 배치를 영원히 멈춘다. PRD §4.8 의 콜당 30s 를 여기 박아
 * S4 가 정책을 세우기 전까지의 안전장치로 삼는다.
 *
 * <p>로깅은 디버그 빌드에서 HEADERS 까지만이다. BODY 로 올리면 base64 이미지 수 MB 가
 * logcat 에 쏟아지고, 무엇보다 요청 헤더에 실린 API 키가 그대로 찍힌다.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class NetworkModule {

    private static final String VERTEX_BASE_URL = "https://aiplatform.googleapis.com/";

    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long READ_TIMEOUT_SECONDS = 30L;

    private NetworkModule() {}

    @Provides
    @Singleton
    public static Gson provideGson() {
        return new GsonBuilder().create();
    }

    @Provides
    @Singleton
    public static OkHttpClient provideOkHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.HEADERS
                : HttpLoggingInterceptor.Level.NONE);
        logging.redactHeader("x-goog-api-key");
        logging.redactHeader("X-Goog-Api-Key");
        return new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    @Provides
    @Singleton
    public static VertexApi provideVertexApi(OkHttpClient client, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(VERTEX_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(VertexApi.class);
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*VertexGeminiProviderTest*'`
Expected: PASS 3건.

> `sendsTheResolvedModelIdAndKey` 가 빈 문자열로 실패하면 `local.properties` 에 `VERTEX_API_KEY` 가 비어 있는 것이다. 테스트는 값이 아니라 **길이 > 0** 만 보므로, 키가 없으면 이 어서션 하나만 실패한다 — 그 경우 Task 1 Step 9 를 먼저 처리한다.

- [ ] **Step 7: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/data/vision/VertexApi.java \
        android/app/src/main/java/com/traveltrace/app/data/vision/VertexGeminiProvider.java \
        android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java \
        android/app/src/test/java/com/traveltrace/app/data/vision/VertexGeminiProviderTest.java
git commit -m "feat: call Vertex AI Express Mode behind the VisionProvider seam

앱 최초의 Retrofit 배선. HTTP 실패(IOException)와 '모델이 모르겠다'(confidence 0)를
타입으로 구분해 S4 의 재시도가 붙을 자리를 만든다. 로깅은 HEADERS 까지만 + API 키
헤더 redact — BODY 로 올리면 base64 이미지와 키가 통째로 logcat 에 찍힌다."
```

---

## Task 5: 좌표화 라우팅 · 동명 지명 판정 (순수 함수)

PRD §4.4 의 분기와 §4.3 의 disambiguation 을 HTTP 없이 결정 가능한 두 순수 클래스로 분리한다. "에펠탑을 Geocoding 에 보내면 zero result 가 급증한다"는 실패 모드는 **라우팅 규칙**의 문제이지 네트워크 문제가 아니므로, 네트워크 없이 검증되어야 한다.

**동명 지명 판정 규칙 (설계 근거):** plan/09 는 "결과가 다수면 NAME_ONLY"라고 쓰지만, 이를 `size() != 1` 로 그대로 구현하면 **거의 모든 사진이 NAME_ONLY 로 강등된다** — Places Text Search 는 "에펠탑" 같은 명확한 질의에도 주변 상점·전망대 등 복수 결과를 흔히 돌려준다. 그래서 판정을 "결과 개수"가 아니라 **"후보들이 지리적으로 흩어져 있는가"** 로 바꾼다. 후보가 반경 50km 안에 모여 있으면 같은 장소를 가리키는 것이므로 1번 후보를 채택하고, 파리(프랑스) vs 파리(텍사스)처럼 멀리 떨어져 있으면 진짜 동명 지명이므로 강등한다. 이 규칙이 "확신에 찬 오답 핀 방지"라는 원래 의도를 실제로 달성한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/analysis/GeocodeRouter.java`
- Create: `android/app/src/main/java/com/traveltrace/app/analysis/Disambiguator.java`
- Test: `android/app/src/test/java/com/traveltrace/app/analysis/GeocodeRouterTest.java`
- Test: `android/app/src/test/java/com/traveltrace/app/analysis/DisambiguatorTest.java`

**Interfaces:**
- Consumes: `RecognitionResult`, `GeocodeQuery`(`.Poi(String name)` / `.AdministrativePlace(String text)`), `GeoPoint(double lat, double lng)` — 전부 기존 타입, 변경 없음.
- Produces:
  - `GeocodeRouter.MIN_CONFIDENCE = 0.55d`
  - `GeocodeRouter.queryFor(RecognitionResult)` → `@Nullable GeocodeQuery` (null = 좌표화할 이름이 없음)
  - `Disambiguator.AMBIGUITY_RADIUS_KM = 50d`
  - `Disambiguator.resolve(List<GeoPoint>)` → `@Nullable GeoPoint` (null = 비었거나 동명 지명)

- [ ] **Step 1: 실패하는 라우팅 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/analysis/GeocodeRouterTest.java`:

```java
package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * PRD §4.4 의 핵심 실패 모드를 고정한다: POI 이름을 Geocoding 으로 보내면 zero result 가
 * 급증하고, 행정 지명을 Places 로 보내면 엉뚱한 상호가 걸린다. 어느 API 로 갈지는
 * 네트워크가 아니라 이 함수가 결정하므로 여기서 전부 검증된다.
 */
@RunWith(RobolectricTestRunner.class)
public class GeocodeRouterTest {

    private static RecognitionResult r(String landmark, String city, String country, double conf) {
        return new RecognitionResult(landmark, city, country, conf);
    }

    @Test
    public void landmarkNameRoutesToPlaces() {
        GeocodeQuery q = GeocodeRouter.queryFor(r("에펠탑", "파리", "프랑스", 0.9));
        assertTrue("POI 는 Places Text Search 로 — 의미 기반 질의에 강하다",
                q instanceof GeocodeQuery.Poi);
        assertEquals("에펠탑", ((GeocodeQuery.Poi) q).name);
    }

    @Test
    public void cityAndCountryRouteToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, "파리", "프랑스", 0.8));
        assertTrue("행정 지명은 Geocoding 으로", q instanceof GeocodeQuery.AdministrativePlace);
        assertEquals("파리, 프랑스", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void cityAloneStillRoutesToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, "파리", null, 0.8));
        assertEquals("파리", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void countryAloneStillRoutesToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, null, "프랑스", 0.8));
        assertEquals("프랑스", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void landmarkWinsOverCityWhenBothArePresent() {
        GeocodeQuery q = GeocodeRouter.queryFor(r("루브르 박물관", "파리", "프랑스", 0.9));
        assertTrue("더 구체적인 위치를 버리고 도시 중심점을 찍을 이유가 없다",
                q instanceof GeocodeQuery.Poi);
    }

    @Test
    public void noNamesMeansNothingToGeocode() {
        assertNull(GeocodeRouter.queryFor(r(null, null, null, 0.9)));
    }

    @Test
    public void blankNamesAreTreatedAsAbsent() {
        assertNull(GeocodeRouter.queryFor(r("", "   ", "", 0.9)));
    }

    @Test
    public void lowConfidenceIsNotThisFunctionsJob() {
        // 저신뢰 강등(NAME_ONLY)은 AiLocationResolver 가 한다 — 여기서 null 을 돌려주면
        // 이름을 보존한 채 강등한다는 구분(NAME_ONLY vs UNKNOWN)이 무너진다.
        assertTrue(GeocodeRouter.queryFor(r("에펠탑", null, null, 0.01)) instanceof GeocodeQuery.Poi);
    }

    @Test
    public void confidenceThresholdIsUsablyLenient() {
        assertTrue("임계값이 너무 높으면 대부분이 NAME_ONLY 로 떨어져 AI 가 무의미해진다",
                GeocodeRouter.MIN_CONFIDENCE > 0d && GeocodeRouter.MIN_CONFIDENCE < 0.8d);
    }
}
```

- [ ] **Step 2: 실패하는 동명 지명 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/analysis/DisambiguatorTest.java`:

```java
package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.traveltrace.app.core.model.GeoPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "확신에 찬 오답 핀"을 막는 마지막 관문 (PRD §4.3).
 *
 * <p>plan/09 의 문구는 "결과가 다수면 강등"이지만 그대로 구현하면 못 쓴다 — Places 는
 * 명확한 질의에도 주변 시설을 함께 돌려주므로 거의 모든 사진이 NAME_ONLY 가 된다.
 * 그래서 개수가 아니라 <em>흩어짐</em>으로 판정한다: 후보가 한 도시 안에 모여 있으면
 * 같은 장소를 가리키는 것이고, 대륙을 건너 흩어져 있으면 진짜 동명 지명이다.
 */
@RunWith(RobolectricTestRunner.class)
public class DisambiguatorTest {

    private static final GeoPoint PARIS_FR = new GeoPoint(48.8584, 2.2945);
    private static final GeoPoint PARIS_TX = new GeoPoint(33.6609, -95.5555);
    private static final GeoPoint LOUVRE = new GeoPoint(48.8606, 2.3376);

    @Test
    public void singleCandidateIsAccepted() {
        GeoPoint resolved = Disambiguator.resolve(Collections.singletonList(PARIS_FR));
        assertNotNull(resolved);
        assertEquals(48.8584, resolved.lat, 1e-9);
    }

    @Test
    public void emptyMeansUnresolvable() {
        assertNull(Disambiguator.resolve(Collections.<GeoPoint>emptyList()));
    }

    @Test
    public void nearbyCandidatesCollapseToTheFirst() {
        // 에펠탑과 루브르는 약 3km. Places 가 이렇게 돌려주는 건 동명 지명이 아니라
        // 같은 동네의 인접 시설이다 — 여기서 강등하면 정상 인식이 전부 죽는다.
        GeoPoint resolved = Disambiguator.resolve(Arrays.asList(PARIS_FR, LOUVRE));
        assertNotNull(resolved);
        assertEquals("1번 후보(Places 랭킹 최상위)를 채택한다", 48.8584, resolved.lat, 1e-9);
    }

    @Test
    public void farApartCandidatesAreAmbiguous() {
        // 파리(프랑스) vs 파리(텍사스) — 약 7900km. 어느 쪽을 찍어도 절반은 오답이다.
        assertNull("동명 지명은 오답 핀 대신 NAME_ONLY 로 강등한다",
                Disambiguator.resolve(Arrays.asList(PARIS_FR, PARIS_TX)));
    }

    @Test
    public void oneDistantOutlierIsEnoughToAbstain() {
        assertNull(Disambiguator.resolve(Arrays.asList(PARIS_FR, LOUVRE, PARIS_TX)));
    }

    @Test
    public void radiusIsMeasuredFromTheTopCandidate() {
        // 상위 후보 기준 반경 안이면 나머지 후보끼리 얼마나 떨어졌든 상관없다 —
        // 채택할 좌표는 어차피 1번 후보이므로 그 주변의 일관성만 따진다.
        double justInside = Disambiguator.AMBIGUITY_RADIUS_KM - 1d;
        GeoPoint near = new GeoPoint(PARIS_FR.lat + justInside / 111d, PARIS_FR.lng);
        assertNotNull(Disambiguator.resolve(Arrays.asList(PARIS_FR, near)));
    }

    @Test
    public void handlesAntimeridianWithoutFalseAmbiguity() {
        // 경도 179.9 와 -179.9 는 약 22km 떨어져 있다. 단순 뺄셈으로 거리를 재면
        // 359.8도로 계산되어 멀쩡한 인식이 강등된다 — 하버사인이 필요한 이유.
        List<GeoPoint> across = new ArrayList<>();
        across.add(new GeoPoint(1.0, 179.9));
        across.add(new GeoPoint(1.0, -179.9));
        assertNotNull("날짜변경선을 건너도 가까운 건 가까운 것이다",
                Disambiguator.resolve(across));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GeocodeRouterTest*' --tests '*DisambiguatorTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class GeocodeRouter`.

- [ ] **Step 4: `GeocodeRouter` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/analysis/GeocodeRouter.java`:

```java
package com.traveltrace.app.analysis;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.RecognitionResult;

/**
 * 인식 결과 → 어떤 좌표화 API 로 보낼지 (PRD §4.4).
 *
 * <p><b>랜드마크는 Places, 행정 지명은 Geocoding.</b> 방향을 바꾸면 양쪽 다 나빠진다 —
 * Geocoding 은 모호·의미 기반 질의에 약해 "에펠탑"에 zero result 를 내기 쉽고, Places 는
 * "프랑스" 같은 넓은 행정 지명에 엉뚱한 상호를 매칭한다.
 *
 * <p>이 클래스는 <b>신뢰도를 보지 않는다.</b> 저신뢰 강등은 이름을 보존한 채 NAME_ONLY 로
 * 내리는 별개 결정이고 {@link AiLocationResolver} 가 소유한다. 여기서 저신뢰에 null 을
 * 돌려주면 "이름만"과 "위치 미상"의 구분이 사라진다(PRD §4.6).
 */
public final class GeocodeRouter {

    /**
     * 좌표를 찍어도 되는 최소 신뢰도. 너무 높이면 대부분이 NAME_ONLY 로 떨어져 AI 를 붙인
     * 의미가 없어지고, 너무 낮추면 오답 핀이 는다. {@link Disambiguator} 가 2차 방어선을
     * 맡고 있으므로 여기서는 명백한 추측만 걸러내는 수준으로 둔다.
     */
    public static final double MIN_CONFIDENCE = 0.55d;

    private GeocodeRouter() {}

    /** 좌표화할 이름이 없으면 null — 호출자는 이를 "위치 미상"으로 다룬다. */
    @Nullable
    public static GeocodeQuery queryFor(RecognitionResult result) {
        String landmark = trimToNull(result.landmarkName);
        if (landmark != null) {
            // 도시 중심점보다 항상 구체적이므로 도시/국가가 함께 와도 랜드마크가 이긴다.
            return new GeocodeQuery.Poi(landmark);
        }

        String city = trimToNull(result.city);
        String country = trimToNull(result.country);
        if (city != null && country != null) {
            return new GeocodeQuery.AdministrativePlace(city + ", " + country);
        }
        if (city != null) {
            return new GeocodeQuery.AdministrativePlace(city);
        }
        if (country != null) {
            return new GeocodeQuery.AdministrativePlace(country);
        }
        return null;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
```

- [ ] **Step 5: `Disambiguator` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/analysis/Disambiguator.java`:

```java
package com.traveltrace.app.analysis;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.List;

/**
 * 좌표 후보 다수 → 채택할 1개, 또는 "판단 보류"(null) (PRD §4.3 disambiguation).
 *
 * <p>판정 기준은 <b>개수가 아니라 흩어짐</b>이다. plan/09 의 "결과가 다수면 강등"을
 * 문자 그대로 구현하면 Places 가 인접 시설을 함께 돌려주는 정상 응답까지 전부 강등되어
 * AI 경로가 사실상 죽는다. 대신 상위 후보를 기준으로 나머지가 {@link #AMBIGUITY_RADIUS_KM}
 * 안에 있는지 본다 — 한 동네에 모여 있으면 같은 장소, 대륙을 건너면 동명 지명이다.
 *
 * <p>보류(null)의 대가는 "이름만" 표시이고, 오판의 대가는 <em>지도 위의 확신에 찬 오답
 * 핀</em>이다. 후자가 훨씬 나쁘므로 애매하면 보류한다.
 */
public final class Disambiguator {

    /**
     * 같은 장소로 볼 최대 반경(km). 대도시 하나가 통째로 들어가되 인접 도시는 갈라질
     * 크기다 — 파리 시내 전역은 묶이고 파리(텍사스)는 갈린다.
     */
    public static final double AMBIGUITY_RADIUS_KM = 50d;

    private static final double EARTH_RADIUS_KM = 6371.0088d;

    private Disambiguator() {}

    @Nullable
    public static GeoPoint resolve(List<GeoPoint> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        GeoPoint top = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            if (distanceKm(top, candidates.get(i)) > AMBIGUITY_RADIUS_KM) {
                return null;   // 동명 지명 — 찍지 않는다.
            }
        }
        return top;
    }

    /**
     * 하버사인. 경도 뺄셈으로 근사하면 날짜변경선 근처에서 22km 를 39000km 로 재어
     * 멀쩡한 인식을 강등시킨다.
     */
    static double distanceKm(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.lng - a.lng);

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1d, Math.sqrt(h)));
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GeocodeRouterTest*' --tests '*DisambiguatorTest*'`
Expected: PASS 16건.

- [ ] **Step 7: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/analysis/GeocodeRouter.java \
        android/app/src/main/java/com/traveltrace/app/analysis/Disambiguator.java \
        android/app/src/test/java/com/traveltrace/app/analysis/GeocodeRouterTest.java \
        android/app/src/test/java/com/traveltrace/app/analysis/DisambiguatorTest.java
git commit -m "feat: route POI names to Places and admin names to Geocoding, abstain on homonyms

동명 지명 판정을 '결과 개수'가 아니라 '후보의 지리적 흩어짐'으로 한다 — Places 는 명확한
질의에도 인접 시설을 함께 돌려주므로 size()!=1 로 판정하면 정상 인식이 전부 NAME_ONLY 로
죽는다. 상위 후보 반경 50km 밖 후보가 있으면 보류. 거리는 하버사인(날짜변경선)."
```

---

## Task 6: Places · Geocoding REST 구현

`Geocoder` 인터페이스의 실구현. 응답 파싱은 다시 순수 함수로 떼어내 네트워크 없이 검증한다.

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesTextSearchApi.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingApi.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesResponseParser.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingResponseParser.java`
- Create: `android/app/src/main/java/com/traveltrace/app/data/geocode/RoutingGeocoder.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java` (API 2종 추가)
- Test: `android/app/src/test/java/com/traveltrace/app/data/geocode/GeocodeResponseParserTest.java`

**Interfaces:**
- Consumes: `GeocodeQuery`, `GeoPoint`, `Geocoder`(기존 인터페이스, **시그니처 변경 없음**), `BuildConfig.PLACES_API_KEY`, `BuildConfig.GEOCODING_API_KEY`, Task 4 의 `OkHttpClient`·`Gson`.
- Produces:
  - `PlacesResponseParser.parse(JsonObject)` → `List<GeoPoint>`
  - `GeocodingResponseParser.parse(JsonObject)` → `List<GeoPoint>`
  - `RoutingGeocoder implements Geocoder`, 생성자 `@Inject RoutingGeocoder(PlacesTextSearchApi places, GeocodingApi geocoding)`
  - `NetworkModule` 이 `PlacesTextSearchApi`·`GeocodingApi` 추가 제공

- [ ] **Step 1: 실패하는 파서 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/data/geocode/GeocodeResponseParserTest.java`:

```java
package com.traveltrace.app.data.geocode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.GeoPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * zero-result 와 에러 응답은 <em>빈 리스트</em>로 수렴해야 한다 — 호출자가 그걸
 * NAME_ONLY 로 강등한다(plan/09). 여기서 예외를 던지면 zero-result 가 재시도 대상으로
 * 잘못 분류되어 같은 실패에 돈을 세 번 쓴다.
 */
@RunWith(RobolectricTestRunner.class)
public class GeocodeResponseParserTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    // ---------- Places (New) Text Search ----------

    @Test
    public void placesParsesLocationsInRankOrder() {
        List<GeoPoint> points = PlacesResponseParser.parse(json(
                "{\"places\":["
                        + "{\"location\":{\"latitude\":48.8584,\"longitude\":2.2945}},"
                        + "{\"location\":{\"latitude\":48.8606,\"longitude\":2.3376}}]}"));
        assertEquals(2, points.size());
        assertEquals("Places 랭킹 순서를 보존해야 Disambiguator 가 1번을 채택한다",
                48.8584, points.get(0).lat, 1e-9);
        assertEquals(2.2945, points.get(0).lng, 1e-9);
    }

    @Test
    public void placesZeroResultIsAnEmptyListNotAnError() {
        assertTrue(PlacesResponseParser.parse(json("{}")).isEmpty());
        assertTrue(PlacesResponseParser.parse(json("{\"places\":[]}")).isEmpty());
    }

    @Test
    public void placesSkipsEntriesMissingCoordinates() {
        List<GeoPoint> points = PlacesResponseParser.parse(json(
                "{\"places\":[{\"displayName\":{\"text\":\"어딘가\"}},"
                        + "{\"location\":{\"latitude\":1.0,\"longitude\":2.0}}]}"));
        assertEquals("좌표 없는 항목을 0,0 으로 메우면 금지된 (0,0) 핀이 생긴다",
                1, points.size());
        assertEquals(1.0, points.get(0).lat, 1e-9);
    }

    // ---------- Geocoding ----------

    @Test
    public void geocodingParsesResults() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":"
                        + "{\"lat\":48.8566,\"lng\":2.3522}}}]}"));
        assertEquals(1, points.size());
        assertEquals(48.8566, points.get(0).lat, 1e-9);
        assertEquals(2.3522, points.get(0).lng, 1e-9);
    }

    @Test
    public void geocodingZeroResultsIsAnEmptyList() {
        assertTrue(GeocodingResponseParser.parse(
                json("{\"status\":\"ZERO_RESULTS\",\"results\":[]}")).isEmpty());
    }

    @Test
    public void geocodingNonOkStatusYieldsNothing() {
        assertTrue("REQUEST_DENIED 에서 results 를 읽으면 낡은/부분 데이터를 찍는다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"bad key\"}")).isEmpty());
    }

    @Test
    public void geocodingReturnsAllCandidatesForHomonymDetection() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":["
                        + "{\"geometry\":{\"location\":{\"lat\":48.85,\"lng\":2.35}}},"
                        + "{\"geometry\":{\"location\":{\"lat\":33.66,\"lng\":-95.55}}}]}"));
        assertEquals("후보를 잘라내면 Disambiguator 가 동명 지명을 볼 수 없다",
                2, points.size());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GeocodeResponseParserTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class PlacesResponseParser`.

- [ ] **Step 3: 파서 2종을 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesResponseParser.java`:

```java
package com.traveltrace.app.data.geocode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Places API (New) Text Search 응답 → 좌표 후보. 랭킹 순서를 보존한다 —
 * {@link com.traveltrace.app.analysis.Disambiguator} 가 1번 후보를 채택하므로
 * 순서가 곧 선택이다.
 *
 * <p>zero-result·형식 이상은 빈 리스트다. 호출자가 그걸 NAME_ONLY 로 강등한다.
 */
public final class PlacesResponseParser {

    private PlacesResponseParser() {}

    public static List<GeoPoint> parse(JsonObject response) {
        List<GeoPoint> points = new ArrayList<>();
        if (response == null || !response.has("places")) {
            return points;
        }
        JsonElement placesElement = response.get("places");
        if (!placesElement.isJsonArray()) {
            return points;
        }
        JsonArray places = placesElement.getAsJsonArray();
        for (int i = 0; i < places.size(); i++) {
            JsonElement entry = places.get(i);
            if (!entry.isJsonObject()) continue;
            JsonObject location = entry.getAsJsonObject().getAsJsonObject("location");
            // 좌표가 없는 항목은 건너뛴다 — 0 으로 메우면 (0,0) 핀이 된다.
            if (location == null || !location.has("latitude") || !location.has("longitude")) {
                continue;
            }
            points.add(new GeoPoint(
                    location.get("latitude").getAsDouble(),
                    location.get("longitude").getAsDouble()));
        }
        return points;
    }
}
```

Create `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingResponseParser.java`:

```java
package com.traveltrace.app.data.geocode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Geocoding API 응답 → 좌표 후보.
 *
 * <p>Geocoding 은 HTTP 200 에 실패를 실어 보낸다 — {@code status} 가 OK 가 아니면
 * {@code results} 를 읽지 않는다. 이 확인을 빼먹으면 REQUEST_DENIED 응답에서 부분/낡은
 * 배열을 읽어 엉뚱한 핀을 찍는다.
 *
 * <p>후보를 <b>자르지 않는다</b> — 전부 넘겨야 Disambiguator 가 동명 지명을 판정한다.
 */
public final class GeocodingResponseParser {

    private static final String STATUS_OK = "OK";

    private GeocodingResponseParser() {}

    public static List<GeoPoint> parse(JsonObject response) {
        List<GeoPoint> points = new ArrayList<>();
        if (response == null || !response.has("status")) {
            return points;
        }
        if (!STATUS_OK.equals(response.get("status").getAsString())) {
            return points;   // ZERO_RESULTS·REQUEST_DENIED·OVER_QUERY_LIMIT 등.
        }
        JsonElement resultsElement = response.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return points;
        }
        JsonArray results = resultsElement.getAsJsonArray();
        for (int i = 0; i < results.size(); i++) {
            JsonElement entry = results.get(i);
            if (!entry.isJsonObject()) continue;
            JsonObject geometry = entry.getAsJsonObject().getAsJsonObject("geometry");
            if (geometry == null) continue;
            JsonObject location = geometry.getAsJsonObject("location");
            if (location == null || !location.has("lat") || !location.has("lng")) continue;
            points.add(new GeoPoint(
                    location.get("lat").getAsDouble(),
                    location.get("lng").getAsDouble()));
        }
        return points;
    }
}
```

- [ ] **Step 4: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GeocodeResponseParserTest*'`
Expected: PASS 7건.

- [ ] **Step 5: Retrofit 인터페이스 2종과 `RoutingGeocoder` 를 만든다**

Create `android/app/src/main/java/com/traveltrace/app/data/geocode/PlacesTextSearchApi.java`:

```java
package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

/**
 * Places API (New) Text Search. base URL 은 {@code https://places.googleapis.com/}.
 *
 * <p>{@code X-Goog-FieldMask} 는 선택이 아니라 <b>필수</b>다 — 빠지면 400 이다. 또한
 * 요청한 필드가 과금 티어를 결정하므로 좌표만 받는다(Places SDK 대신 REST 를 쓰는 이유
 * 중 하나이며, 블로킹 {@code Geocoder} 계약에도 REST 가 자연스럽게 맞는다).
 */
public interface PlacesTextSearchApi {

    @Headers("Content-Type: application/json")
    @POST("v1/places:searchText")
    Call<JsonObject> searchText(@Header("X-Goog-Api-Key") String apiKey,
                                @Header("X-Goog-FieldMask") String fieldMask,
                                @Body JsonObject body);
}
```

Create `android/app/src/main/java/com/traveltrace/app/data/geocode/GeocodingApi.java`:

```java
package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/** Geocoding API. base URL 은 {@code https://maps.googleapis.com/}. */
public interface GeocodingApi {

    @GET("maps/api/geocode/json")
    Call<JsonObject> geocode(@Query("address") String address, @Query("key") String apiKey);
}
```

Create `android/app/src/main/java/com/traveltrace/app/data/geocode/RoutingGeocoder.java`:

```java
package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.domain.Geocoder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Response;

/**
 * {@link Geocoder} 실구현 (PRD §4.4). 어느 API 로 갈지는 {@link GeocodeQuery} 의
 * <em>타입</em>이 이미 결정해 두었다 — 분기 로직이 여기에 다시 있으면 두 벌이 어긋난다.
 *
 * <p>블로킹이다(인터페이스 계약). 호출자는 {@code AppExecutors.io()} 위에 있다.
 *
 * <p>zero-result 는 빈 리스트로 <em>돌려주고</em>, HTTP 실패는 <em>던진다</em>.
 * 전자는 "이 이름은 좌표화 불가"라는 최종 답이라 재시도가 의미 없고, 후자는 S4 의
 * 재시도가 붙어야 할 일시적 실패다.
 */
@Singleton
public class RoutingGeocoder implements Geocoder {

    /** 좌표만 받는다 — 필드가 늘면 Places 과금 티어가 올라간다. */
    private static final String PLACES_FIELD_MASK = "places.location";

    /** 동명 지명 판정에 쓸 만큼만. 1이면 Disambiguator 가 볼 게 없다. */
    private static final int MAX_PLACES_RESULTS = 5;

    private final PlacesTextSearchApi places;
    private final GeocodingApi geocoding;

    @Inject
    public RoutingGeocoder(PlacesTextSearchApi places, GeocodingApi geocoding) {
        this.places = places;
        this.geocoding = geocoding;
    }

    @Override
    public List<GeoPoint> geocode(GeocodeQuery query) throws Exception {
        if (query instanceof GeocodeQuery.Poi) {
            return searchPlaces(((GeocodeQuery.Poi) query).name);
        }
        if (query instanceof GeocodeQuery.AdministrativePlace) {
            return searchGeocoding(((GeocodeQuery.AdministrativePlace) query).text);
        }
        return Collections.emptyList();
    }

    private List<GeoPoint> searchPlaces(String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("textQuery", name);
        body.addProperty("maxResultCount", MAX_PLACES_RESULTS);

        Response<JsonObject> response = places.searchText(
                BuildConfig.PLACES_API_KEY, PLACES_FIELD_MASK, body).execute();
        return unwrap(response, "Places searchText");
    }

    private List<GeoPoint> searchGeocoding(String address) throws IOException {
        Response<JsonObject> response =
                geocoding.geocode(address, BuildConfig.GEOCODING_API_KEY).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Geocoding HTTP " + response.code());
        }
        JsonObject payload = response.body();
        // status 확인은 파서가 한다 — Geocoding 은 200 에 실패를 실어 보낸다.
        return payload == null
                ? Collections.<GeoPoint>emptyList()
                : GeocodingResponseParser.parse(payload);
    }

    private static List<GeoPoint> unwrap(Response<JsonObject> response, String what)
            throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException(what + " HTTP " + response.code());
        }
        JsonObject payload = response.body();
        return payload == null
                ? Collections.<GeoPoint>emptyList()
                : PlacesResponseParser.parse(payload);
    }
}
```

- [ ] **Step 6: `NetworkModule` 에 API 2종을 추가한다**

`android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java` 에 import 와 상수, provider 2개를 더한다:

```java
import com.traveltrace.app.data.geocode.GeocodingApi;
import com.traveltrace.app.data.geocode.PlacesTextSearchApi;
```

```java
    private static final String PLACES_BASE_URL = "https://places.googleapis.com/";
    private static final String GEOCODING_BASE_URL = "https://maps.googleapis.com/";
```

```java
    @Provides
    @Singleton
    public static PlacesTextSearchApi providePlacesApi(OkHttpClient client, Gson gson) {
        return retrofit(PLACES_BASE_URL, client, gson).create(PlacesTextSearchApi.class);
    }

    @Provides
    @Singleton
    public static GeocodingApi provideGeocodingApi(OkHttpClient client, Gson gson) {
        return retrofit(GEOCODING_BASE_URL, client, gson).create(GeocodingApi.class);
    }

    /** 호스트만 다르고 나머지 설정은 같다 — OkHttp/Gson 은 한 벌을 공유한다. */
    private static Retrofit retrofit(String baseUrl, OkHttpClient client, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }
```

`provideVertexApi` 도 같은 헬퍼를 쓰도록 줄인다:

```java
    @Provides
    @Singleton
    public static VertexApi provideVertexApi(OkHttpClient client, Gson gson) {
        return retrofit(VERTEX_BASE_URL, client, gson).create(VertexApi.class);
    }
```

- [ ] **Step 7: 빌드하고 커밋한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GeocodeResponseParserTest*' && ./gradlew :app:assembleDebug`
Expected: PASS 7건, BUILD SUCCESSFUL.

```bash
git add android/app/src/main/java/com/traveltrace/app/data/geocode \
        android/app/src/main/java/com/traveltrace/app/di/NetworkModule.java \
        android/app/src/test/java/com/traveltrace/app/data/geocode
git commit -m "feat: implement the Geocoder seam over Places Text Search and Geocoding REST

분기는 GeocodeQuery 의 타입이 이미 결정해 두었으므로 여기서 다시 판단하지 않는다.
zero-result 는 빈 리스트로 돌려주고(재시도 무의미) HTTP 실패는 던진다(S4 재시도 대상).
Geocoding 은 200 에 실패를 실어 보내므로 status 를 반드시 확인한다."
```

---

## Task 7: 분류 확정 · 파이프라인 순서 강제

S3 의 심장. `PhotoAnalysisPipeline` 이 **①로컬 EXIF → ②재인코딩 → ③업로드** 순서를 소유하므로, 호출부가 순서를 어길 수 있는 코드 경로 자체가 사라진다(PRD §5, plan/07 의 "단일 파이프라인 컴포넌트로 캡슐화").

**Files:**
- Create: `android/app/src/main/java/com/traveltrace/app/analysis/AiLocationResolver.java`
- Create: `android/app/src/main/java/com/traveltrace/app/analysis/PhotoAnalysisPipeline.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/domain/model/PhotoAnalysis.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/di/AppModule.java`
- Delete: `android/app/src/main/java/com/traveltrace/app/data/VisionProviderStub.java`
- Delete: `android/app/src/main/java/com/traveltrace/app/data/GeocoderStub.java`
- Test: `android/app/src/test/java/com/traveltrace/app/analysis/AiLocationResolverTest.java`

**Interfaces:**
- Consumes: `UploadPreparer`(T2), `GeocodeRouter`·`Disambiguator`(T5), `VisionProvider`·`Geocoder`(기존 인터페이스), `ExifExtractor`(기존).
- Produces:
  - `PhotoAnalysis` 에 추가되는 필드: `@Nullable String landmarkName`, `@Nullable String city`, `@Nullable String country`, `@Nullable Double confidence`, `@Nullable String contentHash`
  - `AiLocationResolver#apply(PhotoAnalysis target, RecognitionResult recognition) throws Exception`
  - `PhotoAnalysisPipeline#analyze(GalleryImage image)` → `PhotoAnalysis`
  - `PhotoAnalysisPipeline#originalAccessFailures()` → `int`, `#resetFailureCount()` → `void`
  - 생성자: `@Inject PhotoAnalysisPipeline(ExifExtractor exif, UploadPreparer preparer, VisionProvider vision, AiLocationResolver resolver)`

- [ ] **Step 1: `PhotoAnalysis` 에 필드를 더한다**

`android/app/src/main/java/com/traveltrace/app/domain/model/PhotoAnalysis.java` 의 `classification` 선언 **아래**에 붙인다. 기존 필드는 건드리지 않는다.

```java
    /** AI 가 인식한 랜드마크/POI 이름. GPS 사진과 인식 실패는 null. */
    @Nullable
    public String landmarkName;

    @Nullable
    public String city;

    @Nullable
    public String country;

    /** AI 인식 신뢰도(0~1). AI 를 타지 않은 사진은 null — 0 이 아니다. */
    @Nullable
    public Double confidence;

    /**
     * 업로드 파이프라인이 스트림을 읽는 김에 계산한 SHA-256 (PRD §4.7).
     *
     * <p>AI 경로를 탄 사진에만 채워진다. GPS 사진은 업로드 자체를 하지 않으므로
     * 다운스케일·해시 비용을 낼 이유가 없다 — 캐시(S4)가 GPS 사진까지 필요로 하면
     * 그때 해시 전용 경로를 따로 만든다.
     */
    @Nullable
    public String contentHash;
```

클래스 javadoc 도 고친다: `/** 사진 1장의 분석 결과(EXIF + AI). 저장 전 단계의 운반 객체. */`

- [ ] **Step 2: 실패하는 분류 테스트를 쓴다**

Create `android/app/src/test/java/com/traveltrace/app/analysis/AiLocationResolverTest.java`:

```java
package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * PRD §4.6 의 분류 3종이 갈리는 지점. 여기가 틀리면 지도에 확신에 찬 오답 핀이 찍히거나
 * (반대로) 멀쩡한 인식이 전부 위치 미상으로 사라진다.
 */
@RunWith(RobolectricTestRunner.class)
public class AiLocationResolverTest {

    private static final GeoPoint EIFFEL = new GeoPoint(48.8584, 2.2945);
    private static final GeoPoint PARIS_TX = new GeoPoint(33.6609, -95.5555);

    private static PhotoAnalysis blank(Long takenAtUtc) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = 1L;
        a.displayName = "IMG_0001.jpg";
        a.takenAtUtc = takenAtUtc;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    /** 테스트 안에서만 쓰는 손수 만든 페이크(저장소 규약: 모킹 라이브러리 없음). */
    private static final class FakeGeocoder implements Geocoder {
        private final List<GeoPoint> results;
        GeocodeQuery received;
        RuntimeException toThrow;

        FakeGeocoder(List<GeoPoint> results) { this.results = results; }

        @Override
        public List<GeoPoint> geocode(GeocodeQuery query) {
            received = query;
            if (toThrow != null) throw toThrow;
            return results;
        }
    }

    @Test
    public void confidentSingleMatchBecomesAnAiPlacedStop() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult("에펠탑", "파리", "프랑스", 0.92));

        assertEquals(LocationSource.AI, a.source);
        assertEquals(LocationClassification.PLACED, a.classification);
        assertEquals(48.8584, a.lat, 1e-9);
        assertEquals(2.2945, a.lng, 1e-9);
        assertEquals("에펠탑", a.landmarkName);
        assertEquals(0.92d, a.confidence, 1e-9);
    }

    @Test
    public void placedWithoutATimestampIsNoTime() throws Exception {
        PhotoAnalysis a = blank(null);

        new AiLocationResolver(new FakeGeocoder(Collections.singletonList(EIFFEL)))
                .apply(a, new RecognitionResult("에펠탑", null, null, 0.9));

        assertEquals("좌표는 있지만 경로 순서에 넣을 수 없다(PRD §4.6)",
                LocationClassification.NO_TIME, a.classification);
        assertEquals(LocationSource.AI, a.source);
    }

    @Test
    public void lowConfidenceKeepsTheNameButDropsTheCoordinates() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult("에펠탑", null, null, 0.2));

        assertEquals(LocationClassification.NAME_ONLY, a.classification);
        assertEquals("이름은 살린다 — '이름만'은 '위치 미상'과 별개 분류다", "에펠탑", a.landmarkName);
        assertNull("확신 없는 좌표를 찍느니 안 찍는다", a.lat);
        assertNull(a.lng);
        assertEquals(LocationSource.NONE, a.source);
        assertNull("저신뢰면 지오코딩 호출 자체를 안 한다 — 낭비다", geocoder.received);
    }

    @Test
    public void homonymsDegradeToNameOnly() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);

        new AiLocationResolver(new FakeGeocoder(Arrays.asList(EIFFEL, PARIS_TX)))
                .apply(a, new RecognitionResult(null, "파리", null, 0.9));

        assertEquals(LocationClassification.NAME_ONLY, a.classification);
        assertNull(a.lat);
        assertEquals("파리", a.city);
    }

    @Test
    public void zeroResultDegradesToNameOnly() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);

        new AiLocationResolver(new FakeGeocoder(Collections.<GeoPoint>emptyList()))
                .apply(a, new RecognitionResult("있을 리 없는 가게", null, null, 0.9));

        assertEquals("좌표화 실패는 이름을 보존한 채 강등", LocationClassification.NAME_ONLY, a.classification);
        assertEquals("있을 리 없는 가게", a.landmarkName);
    }

    @Test
    public void unrecognizedPhotoStaysUnknown() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult(null, null, null, 0d));

        assertEquals("실내·음식 사진은 위치 미상이 정상 결과다",
                LocationClassification.UNKNOWN, a.classification);
        assertNull(a.landmarkName);
        assertNull(geocoder.received);
    }

    @Test
    public void poiNamesGoToPlacesAndAdminNamesToGeocoding() throws Exception {
        FakeGeocoder poi = new FakeGeocoder(Collections.singletonList(EIFFEL));
        new AiLocationResolver(poi).apply(blank(1L), new RecognitionResult("에펠탑", "파리", null, 0.9));
        assertEquals(GeocodeQuery.Poi.class, poi.received.getClass());

        FakeGeocoder admin = new FakeGeocoder(Collections.singletonList(EIFFEL));
        new AiLocationResolver(admin).apply(blank(1L), new RecognitionResult(null, "파리", "프랑스", 0.9));
        assertEquals(GeocodeQuery.AdministrativePlace.class, admin.received.getClass());
    }

    @Test
    public void geocoderFailurePropagatesSoS4CanRetryIt() {
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));
        geocoder.toThrow = new RuntimeException("boom");
        try {
            new AiLocationResolver(geocoder).apply(blank(1L),
                    new RecognitionResult("에펠탑", null, null, 0.9));
            org.junit.Assert.fail("일시적 실패를 NAME_ONLY 로 삼키면 재시도 기회가 사라진다");
        } catch (Exception expected) {
            assertEquals("boom", expected.getMessage());
        }
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AiLocationResolverTest*'`
Expected: 컴파일 실패 — `cannot find symbol: class AiLocationResolver`.

- [ ] **Step 4: `AiLocationResolver` 를 구현한다**

Create `android/app/src/main/java/com/traveltrace/app/analysis/AiLocationResolver.java`:

```java
package com.traveltrace.app.analysis;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 인식 결과 → 최종 분류 (PRD §4.6). 세 갈래로 갈린다:
 *
 * <ul>
 *   <li><b>PLACED</b> — 고신뢰 + 좌표화 성공 + 촬영 시각 있음. 지도·경로에 오른다.</li>
 *   <li><b>NAME_ONLY</b> — 이름은 얻었지만 믿고 찍을 좌표가 없음(저신뢰·동명 지명·
 *       zero result). 이름은 <em>보존</em>하고 지도에서만 뺀다.</li>
 *   <li><b>UNKNOWN</b> — 인식 자체가 실패. 실내·음식·추상 사진에서 정상적으로 발생한다.</li>
 * </ul>
 *
 * <p><b>일시적 실패는 삼키지 않는다.</b> 지오코딩이 던지면 그대로 전파해 S4 의 재시도가
 * 붙을 수 있게 한다 — NAME_ONLY 로 조용히 강등하면 네트워크 끊김 한 번에 여행 전체가
 * 영구히 "이름만"으로 저장된다.
 */
@Singleton
public class AiLocationResolver {

    private final Geocoder geocoder;

    @Inject
    public AiLocationResolver(Geocoder geocoder) {
        this.geocoder = geocoder;
    }

    /** {@code target} 을 제자리에서 갱신한다. GPS 사진에는 호출되지 않는다. */
    public void apply(PhotoAnalysis target, RecognitionResult recognition) throws Exception {
        // 이름은 분류와 무관하게 항상 기록한다 — NAME_ONLY 도 UNKNOWN 도 화면에서
        // 이름을 보여줄 수 있어야 하고(S6), 재분석 없이 나중에 수동 핀을 찍을 근거가 된다.
        target.landmarkName = recognition.landmarkName;
        target.city = recognition.city;
        target.country = recognition.country;
        target.confidence = recognition.confidence;

        GeocodeQuery query = GeocodeRouter.queryFor(recognition);
        if (query == null) {
            target.classification = LocationClassification.UNKNOWN;
            return;   // 좌표화할 이름이 없다 = 인식 실패.
        }
        if (recognition.confidence < GeocodeRouter.MIN_CONFIDENCE) {
            // 저신뢰면 지오코딩을 호출하지도 않는다 — 어차피 안 찍을 좌표에 돈을 쓰지 않는다.
            target.classification = LocationClassification.NAME_ONLY;
            return;
        }

        List<GeoPoint> candidates = geocoder.geocode(query);
        GeoPoint resolved = Disambiguator.resolve(candidates);
        if (resolved == null) {
            // zero result 이거나 동명 지명. 이름은 이미 위에서 보존했다.
            target.classification = LocationClassification.NAME_ONLY;
            return;
        }

        target.lat = resolved.lat;
        target.lng = resolved.lng;
        target.source = LocationSource.AI;
        target.classification = target.takenAtUtc == null
                ? LocationClassification.NO_TIME
                : LocationClassification.PLACED;
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AiLocationResolverTest*'`
Expected: PASS 8건.

- [ ] **Step 6: `PhotoAnalysisPipeline` 을 만든다**

Create `android/app/src/main/java/com/traveltrace/app/analysis/PhotoAnalysisPipeline.java`:

```java
package com.traveltrace.app.analysis;

import android.util.Log;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.VisionProvider;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 사진 1장 → {@link PhotoAnalysis}. <b>전송 파이프라인의 유일한 진입점</b>(PRD §5).
 *
 * <p>순서가 이 클래스 안에 갇혀 있다는 것이 요점이다:
 * <ol>
 *   <li><b>①</b> 원본에서 GPS·촬영 시각 EXIF 를 <em>로컬에서</em> 읽는다.</li>
 *   <li><b>②</b> GPS 가 없을 때만 — 다운스케일 + JPEG 재인코딩(EXIF 제거 보장) + 해시.</li>
 *   <li><b>③</b> 그 산출물만 업로드한다.</li>
 * </ol>
 * 호출부는 {@link #analyze} 하나만 볼 수 있으므로 "EXIF 를 읽기 전에 원본을 올린다"거나
 * "원본을 그대로 올린다"는 코드를 <em>쓸 수가 없다</em>. plan/07 이 요구한 캡슐화다.
 *
 * <p><b>GPS 사진은 ② 이후를 통째로 건너뛴다</b> — 비용(업로드·추론)과 프라이버시(전송)
 * 양쪽에서 이득이고, PRD §4.2 의 우선순위 규칙 그 자체다.
 *
 * <p><b>S3 는 직렬·단일 프로바이더다.</b> 동시 4건·타임아웃·재시도·429 백오프·여행당
 * 비용 상한·(_ID+해시) 캐시는 전부 S4 소관이며, 이 클래스를 감싸는 방식으로 붙는다.
 * OpenAI 폴백은 S5 다. 여기에 미리 넣지 않는다.
 */
@Singleton
public class PhotoAnalysisPipeline {

    private static final String TAG = "PhotoAnalysisPipeline";

    private final ExifExtractor exif;
    private final UploadPreparer preparer;
    private final VisionProvider vision;
    private final AiLocationResolver resolver;

    @Inject
    public PhotoAnalysisPipeline(ExifExtractor exif,
                                 UploadPreparer preparer,
                                 VisionProvider vision,
                                 AiLocationResolver resolver) {
        this.exif = exif;
        this.preparer = preparer;
        this.vision = vision;
        this.resolver = resolver;
    }

    public PhotoAnalysis analyze(GalleryImage image) {
        // ① 로컬 EXIF. 이 호출이 먼저 끝나야 아래로 내려갈 수 있다.
        PhotoAnalysis analysis = exif.extract(image);

        if (analysis.source == LocationSource.GPS) {
            return analysis;   // AI 호출 0회 (PRD §4.2-1).
        }

        try {
            // ② 재인코딩 + 다운스케일 + 해시(스트림 1회 읽기).
            UploadPreparer.Prepared prepared = preparer.prepare(image.contentUri);
            analysis.contentHash = prepared.contentHash;

            // ③ 업로드. 여기 도달하는 바이트는 EXIF 가 제거된 다운스케일 JPEG 뿐이다.
            RecognitionResult recognition = vision.recognize(prepared.jpeg);

            resolver.apply(analysis, recognition);
        } catch (Exception failed) {
            // S3 는 재시도하지 않는다(S4 소관). 사진 1장의 실패가 배치를 멈추면 안 되므로
            // 위치 미상으로 남기고 넘어간다 — extract() 가 이미 UNKNOWN 을 세팅해 두었다.
            Log.w(TAG, "AI 경로 실패, 위치 미상으로 둔다: " + image.displayName, failed);
        }
        return analysis;
    }

    /** 원본 접근 실패 수 — 조용한 GPS 누락은 AI 비용 폭증으로 나타난다(plan/06). */
    public int originalAccessFailures() {
        return exif.originalAccessFailures();
    }

    public void resetFailureCount() {
        exif.resetFailureCount();
    }
}
```

- [ ] **Step 7: DI 를 실구현으로 갈아끼우고 스텁을 지운다**

`android/app/src/main/java/com/traveltrace/app/di/AppModule.java` 를 통째로 교체한다:

```java
package com.traveltrace.app.di;

import com.traveltrace.app.data.geocode.RoutingGeocoder;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.data.vision.VertexGeminiProvider;
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
 * S3 에서 VisionProvider·Geocoder 가 스텁을 벗고 실구현이 됐다. OpenAI 폴백 구현이
 * 들어오는 S5 에서는 여기가 아니라 VisionProvider 뒤의 합성 구현이 바뀐다 — 상위
 * 계층은 provider 종류를 계속 모른다.
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class AppModule {

    @Binds
    @Singleton
    public abstract VisionProvider bindVisionProvider(VertexGeminiProvider impl);

    @Binds
    @Singleton
    public abstract Geocoder bindGeocoder(RoutingGeocoder impl);

    @Binds
    @Singleton
    public abstract TripRepository bindTripRepository(RoomTripRepository impl);

    @Binds
    @Singleton
    public abstract PhotoAnalysisRepository bindPhotoAnalysisRepository(
            RoomPhotoAnalysisRepository impl);
}
```

그리고 두 스텁 파일을 삭제한다:

```bash
git rm android/app/src/main/java/com/traveltrace/app/data/VisionProviderStub.java \
       android/app/src/main/java/com/traveltrace/app/data/GeocoderStub.java
```

- [ ] **Step 8: 빌드하고 커밋한다**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt 그래프가 실구현으로 해소된다).

```bash
git add android/app/src/main/java/com/traveltrace/app/analysis \
        android/app/src/main/java/com/traveltrace/app/domain/model/PhotoAnalysis.java \
        android/app/src/main/java/com/traveltrace/app/di/AppModule.java \
        android/app/src/test/java/com/traveltrace/app/analysis/AiLocationResolverTest.java
git commit -m "feat: enforce the privacy pipeline order in a single component

호출부가 볼 수 있는 진입점이 analyze() 하나뿐이라 'EXIF 를 읽기 전에 올린다'거나
'원본을 그대로 올린다'는 코드를 쓸 수가 없다(PRD §5, plan/07). GPS 사진은 ② 이후를
건너뛰어 AI 호출 0회. 지오코딩 예외는 삼키지 않고 전파해 S4 재시도 자리를 남긴다."
```

---

## Task 8: 결과 저장 + ANALYZE 배선

DB 컬럼은 이미 다 있는데 값이 안 실리고 있다. 그걸 잇고, ViewModel 을 파이프라인으로 갈아끼운다.

**`NAME_ONLY` 표시에 대한 S3 결정:** `AnalysisUiState` 에 `nameOnlyCount` 를 **추가하지 않는다.** NAME_ONLY 전용 UI 는 S6 소관이고, UiState 필드를 늘리면 `ScreenFixtures`·복제 메서드·Renderer 테스트·골든 스크린샷이 함께 움직여 S3 의 위험이 불필요하게 커진다. 그동안 NAME_ONLY 는 **"위치 미상" 카운트에 합산**한다 — 사용자 관점에서 둘 다 "지도에 없는 사진"이므로 거짓말이 아니고, S6 가 분리할 때 카운트 총합이 유지된다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/data/repo/RoomPhotoAnalysisRepository.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/data/repo/RoomTripRepository.java`
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java` (기존 파일 수정)

**Interfaces:**
- Consumes: `PhotoAnalysisPipeline`(T7), `PhotoAnalysis` 의 새 필드(T7).
- Produces: `AnalysisViewModel` 생성자가 `ExifExtractor` 대신 `PhotoAnalysisPipeline` 을 받는다:
  `AnalysisViewModel(Context, MediaStoreImageSource, PhotoAnalysisPipeline, PhotoAnalysisRepository, SelectionSession, AppExecutors)`

- [ ] **Step 1: 리포지토리가 새 필드를 저장하게 한다**

`RoomPhotoAnalysisRepository.java` 의 `PhotoLocationEntity` 조립부(대략 55~62줄)에 4줄을 더한다:

```java
                PhotoLocationEntity l = new PhotoLocationEntity();
                l.photoId = photoId;
                l.lat = a.lat;
                l.lng = a.lng;
                l.source = a.source;
                l.landmarkName = a.landmarkName;
                l.city = a.city;
                l.country = a.country;
                l.confidence = a.confidence;
                l.classification = a.classification;
                l.detached = false;
                locations.add(l);
```

같은 루프의 `PhotoEntity` 조립부에도 1줄을 더한다:

```java
                p.displayName = a.displayName;
                p.contentHash = a.contentHash;
```

`RoomTripRepository.java` 의 `unknownCount` 집계(대략 50~51줄)를 고친다:

```java
                // NAME_ONLY 는 "지도에 없는 사진"이라는 점에서 사용자에겐 위치 미상과 같다.
                // 둘을 화면에서 분리하는 건 S6 이며, 그때 이 합산을 쪼갠다.
                detail.unknownCount = db.photoLocationDao()
                        .countByClassification(tripId, LocationClassification.UNKNOWN)
                        + db.photoLocationDao()
                        .countByClassification(tripId, LocationClassification.NAME_ONLY);
```

- [ ] **Step 2: `AnalysisViewModel` 을 파이프라인으로 갈아끼운다**

import 를 바꾼다: `com.traveltrace.app.data.exif.ExifExtractor` → `com.traveltrace.app.analysis.PhotoAnalysisPipeline`.

필드·생성자:

```java
    private final PhotoAnalysisPipeline pipeline;
```
```java
    @Inject
    public AnalysisViewModel(@ApplicationContext Context context,
                             MediaStoreImageSource imageSource,
                             PhotoAnalysisPipeline pipeline,
                             PhotoAnalysisRepository analysisRepository,
                             SelectionSession session,
                             AppExecutors executors) {
        this.context = context;
        this.imageSource = imageSource;
        this.pipeline = pipeline;
        this.analysisRepository = analysisRepository;
        this.session = session;
        this.executors = executors;
    }
```

`start()` 의 `extractor.resetFailureCount()` → `pipeline.resetFailureCount()`.

`run()` 의 분석 호출과 진행 텍스트:

```java
            GalleryImage image = targets.get(i);
            PhotoAnalysis analysis = pipeline.analyze(image);
            results.add(analysis);

            int analyzed = i + 1;
            int placed = countPlaced(results);
            int unknown = countUnknown(results);
            // AI 가 이름을 알아냈으면 그걸 보여준다 — "현재 인식 텍스트"라는 이름값을
            // 이제야 한다. 못 알아냈으면 S1 처럼 파일명으로 폴백한다.
            String currentName = progressLabel(analysis, image);
            executors.mainThread().execute(() -> state.setValue(
                    new AnalysisUiState(analyzed, total, false, currentName, placed, unknown)));
```

`countUnknown` 을 고치고 `progressLabel` 을 더한다:

```java
    /**
     * NAME_ONLY 를 위치 미상에 합산한다. 사용자에겐 둘 다 "지도에 없는 사진"이고,
     * 이 둘을 화면에서 갈라 보여주는 건 S6 다 — 그때 이 합산을 쪼갠다.
     */
    private static int countUnknown(List<PhotoAnalysis> results) {
        int n = 0;
        for (PhotoAnalysis a : results) {
            if (a.classification == LocationClassification.UNKNOWN
                    || a.classification == LocationClassification.NAME_ONLY) {
                n++;
            }
        }
        return n;
    }

    /** 인식된 장소명 > 도시명 > 파일명. 전부 저장된 값이라 새 문자열 리소스가 필요 없다. */
    private static String progressLabel(PhotoAnalysis analysis, GalleryImage image) {
        if (analysis.landmarkName != null && !analysis.landmarkName.trim().isEmpty()) {
            return analysis.landmarkName;
        }
        if (analysis.city != null && !analysis.city.trim().isEmpty()) {
            return analysis.city;
        }
        return image.displayName;
    }
```

`originalAccessFailures()` 도 위임처를 바꾼다:

```java
    public int originalAccessFailures() {
        return pipeline.originalAccessFailures();
    }
```

클래스 javadoc 의 "S1 엔 AI 가 없으므로 ... 파일명을 보여준다" 문단을 갈아끼운다:

```java
 * <p>사진 1장의 분석은 {@link PhotoAnalysisPipeline} 이 통째로 소유한다 — 이 ViewModel 은
 * GPS 냐 AI 냐를 알지 못하며, 알 필요도 없다. S3 는 <em>직렬</em> 처리다(동시 4건·재시도·
 * 비용 상한은 S4 가 이 루프를 대체하며 붙인다).
```

- [ ] **Step 3: 기존 테스트를 고치고 새 어서션을 더한다**

`android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java` 를 연다.

`setUp()` 의 ViewModel 조립을 바꾼다(나머지 setUp 은 그대로):

```java
        vision = new ScriptedVisionProvider();
        geocoder = new ScriptedGeocoder();
        vm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new PhotoAnalysisPipeline(
                        new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                        new UploadPreparer(ctx),
                        vision,
                        new AiLocationResolver(geocoder)),
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors);
```

필드 2개를 선언한다: `private ScriptedVisionProvider vision;` / `private ScriptedGeocoder geocoder;`

**`AnalysisViewModel` 생성자 호출부는 이 파일에 두 곳이다.** `setUp()`(대략 69줄) 말고 `sessionClearOrderingSurvivesACommitThenCancelRace()` 안의 `raceVm`(대략 328줄)도 같은 시그니처로 고쳐야 한다 — 안 고치면 모듈 전체의 테스트 컴파일이 깨져 Step 4 검증 자체가 불가능하다:

```java
        AnalysisViewModel raceVm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new PhotoAnalysisPipeline(
                        new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                        new UploadPreparer(ctx),
                        new ScriptedVisionProvider(),
                        new AiLocationResolver(new ScriptedGeocoder())),
                fakeRepo,
                session,
                executors);
```

(세 번째 인자만 바뀐다 — `fakeRepo` 는 그 테스트가 이미 쓰던 `CommitThenCancelRepository` 인스턴스 그대로다.) 이 테스트의 의도는 저장 커밋과 취소의 경쟁 상태이지 AI 가 아니므로, 기본값(인식 실패) 페이크로 충분하다.

클래스 하단에 페이크 2개를 더한다(기존 `CommitThenCancelRepository` 옆, 같은 스타일):

```java
    /** 기본은 인식 실패. 테스트가 명시적으로 결과를 심을 때만 성공한다. */
    private static final class ScriptedVisionProvider implements VisionProvider {
        RecognitionResult next = VertexResponseParser.UNRECOGNIZED;
        int calls;

        @Override
        public RecognitionResult recognize(byte[] imageJpeg) {
            calls++;
            return next;
        }
    }

    private static final class ScriptedGeocoder implements Geocoder {
        List<GeoPoint> next = Collections.emptyList();

        @Override
        public List<GeoPoint> geocode(GeocodeQuery query) {
            return next;
        }
    }
```

기존 `gpsPhotosBecomeRouteAndTheRestBecomeUnknown` 을 **아래 3개로 교체**한다. (사진 1·2 는 GPS 있음, 3 은 없음 — 기존 `seedGallery()` 의 전제를 그대로 쓴다.)

```java
    @Test
    public void gpsPhotosSkipAiEntirely() {
        session.put(Arrays.asList(1L, 2L, 3L));
        vm.start();
        drain();

        assertEquals("GPS 사진은 AI 를 타지 않는다 — 비용·프라이버시 양쪽의 계약(PRD §4.2)",
                1, vision.calls);
    }

    @Test
    public void recognizedPhotoBecomesAnAiStopOnTheRoute() {
        vision.next = new RecognitionResult("에펠탑", "파리", "프랑스", 0.9);
        geocoder.next = Collections.singletonList(new GeoPoint(48.8584, 2.2945));
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertEquals("GPS 2장 + AI 1장이 모두 경로에 오른다", 3, state.routeCount);
        assertEquals(0, state.unknownCount);
    }

    @Test
    public void unrecognizedPhotoStaysUnknown() {
        // vision.next 는 기본값(UNRECOGNIZED) 그대로.
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertEquals("GPS 2장만 경로에", 2, state.routeCount);
        assertEquals("인식 실패한 1장은 위치 미상", 1, state.unknownCount);
    }
```

필요한 import 를 더한다:

```java
import com.traveltrace.app.analysis.AiLocationResolver;
import com.traveltrace.app.analysis.PhotoAnalysisPipeline;
import com.traveltrace.app.analysis.UploadPreparer;
import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.data.vision.VertexResponseParser;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.VisionProvider;
import java.util.Collections;
```

그리고 클래스 선언 위에 `@GraphicsMode(GraphicsMode.Mode.NATIVE)` 를 붙인다(`import org.robolectric.annotation.GraphicsMode;`) — AI 경로가 실제로 `UploadPreparer` 를 타므로 진짜 JPEG 인코더가 필요하다.

**마지막으로 `registerJpeg()` 에 스트림 등록 1줄을 더한다 — 이걸 빼면 위 테스트 3개가 전부 실패한다.**

지금 `registerJpeg()` 는 `MediaStore.setRequireOriginal(uri)` 에만 스트림을 등록한다. `ExifExtractor` 는 그 URI 로 열지만 `UploadPreparer` 는 **일반 URI** 로 연다(원본 권한이 필요 없는 업로드 경로이므로 — `UploadPreparer` javadoc 참조). `ShadowContentResolver#registerInputStream` 은 URI 가 정확히 일치할 때만 매칭하므로, 일반 URI 로 열면 `null` 이 돌아와 AI 경로가 통째로 `IOException` 으로 죽고 사진 3 이 영원히 UNKNOWN 이 된다.

`registerInputStream(MediaStore.setRequireOriginal(uri), ...)` 줄 **바로 아래**에 더한다:

```java
        // UploadPreparer 는 원본이 아닌 일반 URI 로 연다(업로드 경로엔 위치가 필요 없고,
        // scoped storage 가 이미 위치 EXIF 를 가려준 스트림이면 충분하다). 섀도 리졸버는
        // Uri 가 정확히 일치할 때만 매칭하므로 두 Uri 에 각각 등록해야 한다.
        // FileInputStream 은 1회용이라 같은 인스턴스를 재사용할 수 없다 — 새로 연다.
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new FileInputStream(file));
```

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: 전부 PASS. **골든 스크린샷 14장 전부 불변** — `AnalysisUiState` 를 안 바꿨으므로 Renderer 테스트도 그대로 통과해야 한다. 하나라도 깨지면 UiState 를 건드린 것이니 되돌린다.

- [ ] **Step 5: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/data/repo \
        android/app/src/main/java/com/traveltrace/app/ui/analysis/AnalysisViewModel.java \
        android/app/src/test/java/com/traveltrace/app/ui/analysis/AnalysisPipelineTest.java
git commit -m "feat: persist AI names and route ANALYZE through the pipeline

컬럼(landmarkName/city/country/confidence/contentHash)은 S1 부터 있었지만 값이 실린 적이
없었다. NAME_ONLY 는 당분간 '위치 미상'에 합산한다 — 사용자에겐 둘 다 지도에 없는 사진이고,
분리는 S6 소관이다. UiState 는 건드리지 않아 골든 14장 전부 불변."
```

---

## Task 9: 지도에 "근사 위치" 시각 구분

마지막 조각. 데이터는 이미 `Stop.ai` 까지 흘러오고 바텀시트 배지·"빼기" 버튼도 S1 에서 완성됐다 — **지도 그리기만** 남았다.

**Files:**
- Modify: `android/app/src/main/java/com/traveltrace/app/ui/map/MapRouteRenderer.java`
- Test: `android/app/src/test/java/com/traveltrace/app/ui/map/MapRouteRendererTest.java` (기존 파일에 추가)

**Interfaces:**
- Consumes: `MapUiState.Stop`(기존, `.ai` 필드 포함), `R.drawable.pin_gps`·`R.drawable.pin_approx`(둘 다 존재).
- Produces: `MapRouteRenderer.dashedSegments(List<MapUiState.Stop>)` → `boolean[]` (길이 = `max(0, stops.size()-1)`), `MapRouteRenderer.pinResFor(boolean ai)` → `int`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MapRouteRendererTest.java` 끝에 더한다. **기존 `boundsOf`/`cameraFor` 테스트는 건드리지 않는다.**

```java
    private static MapUiState.Stop stop(double lat, double lng, boolean ai) {
        return new MapUiState.Stop("p" + lat, "이름", "09:00", ai, 0, 0xFFEEEEEE, lat, lng);
    }

    @Test
    public void aiStopsGetTheApproximatePin() {
        assertEquals(R.drawable.pin_approx, MapRouteRenderer.pinResFor(true));
        assertEquals(R.drawable.pin_gps, MapRouteRenderer.pinResFor(false));
    }

    @Test
    public void segmentTouchingAnAiStopIsDashed() {
        boolean[] dashed = MapRouteRenderer.dashedSegments(Arrays.asList(
                stop(1, 1, false), stop(2, 2, true), stop(3, 3, false)));
        assertEquals(2, dashed.length);
        assertTrue("AI 스톱으로 들어가는 구간은 추정 경로다", dashed[0]);
        assertTrue("AI 스톱에서 나가는 구간도 마찬가지다", dashed[1]);
    }

    @Test
    public void segmentBetweenTwoGpsStopsIsSolid() {
        boolean[] dashed = MapRouteRenderer.dashedSegments(Arrays.asList(
                stop(1, 1, false), stop(2, 2, false)));
        assertEquals(1, dashed.length);
        assertFalse("GPS 끼리는 실측 경로다 — 점선으로 그리면 정확도를 스스로 깎는다", dashed[0]);
    }

    @Test
    public void singleStopHasNoSegments() {
        assertEquals(0, MapRouteRenderer.dashedSegments(
                Collections.singletonList(stop(1, 1, true))).length);
    }

    @Test
    public void emptyStopsHaveNoSegments() {
        assertEquals(0, MapRouteRenderer.dashedSegments(
                Collections.<MapUiState.Stop>emptyList()).length);
    }
```

필요한 import: `com.traveltrace.app.R`, `java.util.Arrays`, `java.util.Collections`, `static org.junit.Assert.assertFalse/assertTrue` (이미 있으면 중복 추가 금지).

- [ ] **Step 2: 테스트가 실패하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapRouteRendererTest*'`
Expected: 컴파일 실패 — `cannot find symbol: method dashedSegments`.

- [ ] **Step 3: `MapRouteRenderer` 를 고친다**

`draw()` 와 `pinIcon()` 을 아래로 **교체**한다. `boundsOf`·`cameraFor`·`SINGLE_STOP_ZOOM` 은 그대로 둔다.

```java
    /** 점선 대시·간격 길이(px). 실선과 확실히 구분되되 산만하지 않은 크기. */
    private static final float DASH_PX = 18f;
    private static final float GAP_PX = 12f;

    /** 기존 마커·경로를 지우고 다시 그린다. */
    public static void draw(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        map.clear();
        if (stops.isEmpty()) return;

        // 두 아이콘을 루프 밖에서 1회만 굽는다 — 사진마다 비트맵을 새로 만들면
        // 100장짜리 여행에서 눈에 띄게 버벅인다.
        BitmapDescriptor gpsPin = pinIcon(context, R.drawable.pin_gps);
        BitmapDescriptor approxPin = pinIcon(context, R.drawable.pin_approx);

        for (MapUiState.Stop stop : stops) {
            map.addMarker(new MarkerOptions()
                    .position(new LatLng(stop.lat, stop.lng))
                    .title(stop.name)
                    .icon(stop.ai ? approxPin : gpsPin));
        }
        drawRoute(map, stops, context);
    }

    /**
     * AI 근사 위치가 끼면 그 구간을 점선으로 그린다 (PRD §4.4 "근사 위치 시각 구분").
     *
     * <p>연속된 같은 종류의 구간을 하나의 폴리라인으로 묶는다 — 구간마다 폴리라인을
     * 만들면 100장 여행에서 오버레이가 99개 생긴다.
     */
    private static void drawRoute(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        boolean[] dashed = dashedSegments(stops);
        if (dashed.length == 0) return;   // 스톱이 1곳이면 그릴 선이 없다.

        float width = context.getResources().getDimension(R.dimen.map_route_width);
        int color = ContextCompat.getColor(context, R.color.fill_brand);

        int i = 0;
        while (i < dashed.length) {
            boolean isDashed = dashed[i];
            PolylineOptions segment = new PolylineOptions().width(width).color(color);
            segment.add(new LatLng(stops.get(i).lat, stops.get(i).lng));

            int j = i;
            while (j < dashed.length && dashed[j] == isDashed) {
                segment.add(new LatLng(stops.get(j + 1).lat, stops.get(j + 1).lng));
                j++;
            }
            if (isDashed) {
                segment.pattern(Arrays.asList(new Dash(DASH_PX), new Gap(GAP_PX)));
            }
            map.addPolyline(segment);
            i = j;
        }
    }

    /**
     * 구간별 점선 여부. 양 끝 중 하나라도 AI 근사 위치면 그 구간은 추정 경로다 —
     * 실제로 어디를 지나갔는지 모르는 구간을 실선으로 그리면 없는 정확도를 주장하게 된다.
     *
     * <p>SDK 타입을 쓰지 않아 단위 테스트가 가능하다({@code sameRoute} 와 같은 이유).
     */
    public static boolean[] dashedSegments(List<MapUiState.Stop> stops) {
        if (stops == null || stops.size() < 2) {
            return new boolean[0];
        }
        boolean[] dashed = new boolean[stops.size() - 1];
        for (int i = 0; i < dashed.length; i++) {
            dashed[i] = stops.get(i).ai || stops.get(i + 1).ai;
        }
        return dashed;
    }

    /** GPS 핀과 AI 근사 위치 핀은 시각적으로 달라야 한다(PRD §4.4). */
    public static int pinResFor(boolean ai) {
        return ai ? R.drawable.pin_approx : R.drawable.pin_gps;
    }

    /** 벡터 드로어블은 BitmapDescriptorFactory 가 직접 못 읽어 비트맵으로 굽는다. */
    private static BitmapDescriptor pinIcon(Context context, int drawableRes) {
        Drawable drawable = ContextCompat.getDrawable(context, drawableRes);
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
```

import 를 더한다:

```java
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;

import java.util.Arrays;
```

클래스 javadoc 의 "S1 은 GPS 핀만 그린다..." 문단을 교체한다:

```java
 * <p>GPS 핀은 실선·{@code pin_gps}, AI 근사 위치는 점선·{@code pin_approx} 로 그린다.
 * 구간은 양 끝 중 하나라도 AI 면 점선이다 — 어디를 지나갔는지 모르는 구간을 실선으로
 * 그리면 없는 정확도를 주장하게 된다(PRD §4.4).
```

- [ ] **Step 4: 테스트가 통과하는 걸 확인한다**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*MapRouteRendererTest*'`
Expected: PASS (새 5건 + 기존 전부).

- [ ] **Step 5: 전체 검증**

Run: `cd android && ./gradlew :app:testDebugUnitTest && ./gradlew :buildSrc:test && ./gradlew :app:assembleDebug`
Expected: 전부 BUILD SUCCESSFUL. 골든 14장 불변.

Run: `cd android && ./gradlew check`
Expected: Task 1 Step 9(`resolveVisionModels`)를 실행했다면 통과. **아직 안 했다면 여기서 룰셋 불일치로 하드 실패한다 — 그게 이 가드의 존재 이유다.** 실 키를 확보해 Step 9 를 먼저 끝낸다.

- [ ] **Step 6: 커밋한다**

```bash
git add android/app/src/main/java/com/traveltrace/app/ui/map/MapRouteRenderer.java \
        android/app/src/test/java/com/traveltrace/app/ui/map/MapRouteRendererTest.java
git commit -m "feat: draw AI stops with the approximate pin and a dashed route

데이터(Stop.ai)와 바텀시트 배지는 S1 부터 있었고 지도 그리기만 비어 있었다. 구간은
양 끝 중 하나라도 AI 면 점선 — 실제 경로를 모르는 구간을 실선으로 그리면 없는 정확도를
주장하게 된다. 아이콘 2종은 루프 밖에서 1회만 굽는다."
```

- [ ] **Step 7: 기기에서 실제로 확인한다 (수동, 자동화 불가)**

JVM 테스트로는 증명할 수 없는 것들이다. 실기기에서 1회 확인한다:

1. GPS 없는 사진 3~5장을 골라 분석 → ANALYZE 진행 텍스트에 **파일명이 아니라 장소명**이 뜨는가.
2. MAP 에서 AI 핀이 GPS 핀과 **눈에 띄게 다르고**, 그 구간이 **점선**인가.
3. AI 핀을 탭하면 바텀시트에 "근사 위치" 배지와 "빼기" 버튼이 뜨는가(S1 에서 만든 UI 가 실데이터로 처음 살아나는 순간).
4. 실내/음식 사진이 크래시 없이 "위치 미상"으로 떨어지는가.
5. HEIC 사진(아이폰 촬영본)과 RAW 각 1장 — 크래시 없이 처리되는가(Task 2 의 수동 검증 항목).
6. 비행기 모드로 분석 → 크래시 없이 전부 위치 미상으로 떨어지는가.

---

## S3 완료 조건 대조 (plan/ISSUES.md:97-118)

| Acceptance criterion | 충족 근거 |
|---|---|
| 업로드 산출물에 GPS/시각 EXIF 없음 (JPEG/HEIC/RAW) | Task 2 — `strippedOutputHasNoGpsOrTimestampExif` + `outputIsAlwaysJpegRegardlessOfInput`(포맷 분기 부재가 계약). HEIC/RAW 는 JVM 에서 합성 불가라 Task 9 Step 7-5 의 기기 확인으로 보완 |
| "에펠탑"→Places, "파리, 프랑스"→Geocoding 라우팅 | Task 5 — `landmarkNameRoutesToPlaces`, `cityAndCountryRouteToGeocoding`, `landmarkWinsOverCityWhenBothArePresent` |
| 근사 위치 핀이 GPS 핀과 시각 구분 | Task 9 — `aiStopsGetTheApproximatePin`, `segmentTouchingAnAiStopIsDashed` + 기기 확인 |
| 동명/저신뢰가 `이름만`으로 강등, 오답 핀 없음 | Task 7 — `homonymsDegradeToNameOnly`, `lowConfidenceKeepsTheNameButDropsTheCoordinates`, `zeroResultDegradesToNameOnly` |
| 파이프라인 순서(①→②→③) 강제 | Task 7 — `PhotoAnalysisPipeline` 이 유일한 진입점. 스텁 삭제로 우회 경로도 사라짐 |
| AI 는 좌표를 만들지 않는다 | Task 3 — `schemaHasNoCoordinateFields` + `RecognitionResult` 에 좌표 필드 부재(불변) |
| 단일 프로바이더 | OpenAI 배선 없음. `BuildConfig.OPENAI_*` 는 S5 용으로 미사용 상태 유지 |

**의도적으로 하지 않은 것** (전부 후속 슬라이스): 동시 4건·타임아웃 정책·재시도·429 백오프·여행당 비용 상한·`AnalysisCache`(S4) / OpenAI 폴백·엔진 모드 토글(S5) / NAME_ONLY 전용 UI·detach 재구성(S6).
