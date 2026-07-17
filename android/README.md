# TravelTrace (Epic A — 프로젝트 기반)

여행 사진을 골라 촬영 시각순 경로로 지도 위에 리플레이하는 Android 앱. 본 저장소의 현재 상태는
**Epic A(프로젝트 기반)** 골격이다 — 빌드되는 빈 앱 + 빌드시점 Vision 모델 ID 확정 메커니즘 +
API 키/quota 안전장치. 제품 사양은 [PRD.md](../PRD.md), 작업 분할은 [plan/](../plan/) 참조.

> **개발 언어 (v5): Java + View/XML.** UI는 Fragment + Navigation Component(XML nav graph) + ViewBinding,
> 비동기는 ExecutorService, 직렬화는 Gson, Hilt/Room은 annotationProcessor. Gradle은 Groovy DSL, buildSrc는 Java.

---

## 빌드 / 실행

전제: Android Studio + Android SDK. 이 개발 머신 기준:
- SDK: `D:\Android\Sdk` (설치됨: android-34 / build-tools 34.0.0). `android/local.properties`의
  `sdk.dir`이 이미 이 경로를 가리킨다.
- JDK 17: Android Studio 번들(`D:\Program Files\Android\Android Studio\jbr`).
- 프로젝트는 `compileSdk = 35 / targetSdk = 35`이므로 **첫 Gradle 동기화 때 Studio가 android-35 +
  build-tools 35를 자동 다운로드**한다(인터넷 1회 필요).

이 저장소는 Gradle wrapper(`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, 8.11.1)를
포함하므로 clone 후 별도 부트스트랩 없이 바로 빌드된다.

- **CLI (권장)**: `android/`에서 `./gradlew :app:assembleDebug` — 첫 실행 시 wrapper가 Gradle 8.11.1을
  자동으로 내려받는다(인터넷 1회 필요).
- **Android Studio**: `Open` → `android/` 폴더 선택. SDK/JDK 연결과 누락 플랫폼(android-35) 다운로드를 처리한다.

> **CLI 트러블슈팅 (사용자명에 비ASCII 문자가 있는 Windows):** 기본 `GRADLE_USER_HOME`
> (`C:\Users\<사용자명>\.gradle`) 경로에 한글 등 비ASCII 문자가 있으면 Gradle 테스트 워커가
> classpath argfile을 CP949로 못 읽어 `ClassNotFoundException: ...GradleWorkerMain`(exit 1)으로
> 죽는다(특히 `:buildSrc:test`). ASCII 경로를 지정해 우회한다:
> `set GRADLE_USER_HOME=D:\gradle_home_ascii` (CMD) / `$env:GRADLE_USER_HOME='D:\gradle_home_ascii'` (PowerShell).
> Android Studio는 자체 홈을 써서 영향받지 않는다.

키 설정:

```bash
cp local.properties.example local.properties
# local.properties 에 API 키 입력 (아래 "API 키" 참조). 일반 빌드엔 MAPS/PLACES/GEOCODING만 있으면 된다.
```

주요 명령:

| 명령 | 설명 |
|------|------|
| `./gradlew :app:assembleDebug` | 디버그 APK 빌드 (네트워크/Vision 키 불필요 — 커밋된 모델 ID 사용) |
| `./gradlew :buildSrc:test` | A2 선정 로직 + 가드 task 빌드실패 테스트 |
| `./gradlew check` | 린트 + `modelIdGuard`(죽은 ID 하드코딩) + `visionModelStalenessGuard` |
| `./gradlew resolveVisionModels` | **명시적** 라이브 모델 재해소 (GEMINI/OPENAI 키 + 네트워크 필요) |

---

## A2 — 빌드 시점 Vision 모델 ID 확정

모델 ID는 하드코딩하지 않는다(폐기 위험, PRD §4.3/§8). 대신:

- **해소는 빌드와 분리된 명시적 스텝**이다: `./gradlew resolveVisionModels`가 라이브 모델 목록
  (Gemini `GET /v1beta/models`, OpenAI `GET /v1/models`)을 조회해 적합 vision 모델을 고르고,
  결과를 **VCS에 커밋되는** [`gradle/visionModels.generated.json`](gradle/visionModels.generated.json)에 기록한다.
- **모든 일반 빌드**는 이 커밋된 파일을 **configuration 단계에서 읽어** `BuildConfig.GEMINI_VISION_MODEL`/
  `OPENAI_VISION_MODEL`로 주입한다 → 네트워크·키 없이 **재현 가능·오프라인** 빌드.
- **적합 모델 0개 → 빌드 실패.** `resolveVisionModels`는 적합 모델이 없으면 `NoSuitableModelException`을
  내고, 빌드는 생성 파일이 없거나 비면 실패한다. 키 부재("API key missing")는 적합 0개와 **구분된** 에러다.
- **죽은 ID 가드**(`modelIdGuard`): `app/src/main`의 소스에서 모델 ID 문자열 리터럴을 발견하면 빌드 실패
  (테스트/생성 코드는 스캔 제외).
- **staleness 가드**(`visionModelStalenessGuard`, `check`에 연결): 룰셋 버전 불일치는 **실패**(릴리스 파이프라인이
  `check` 실행), 경과일 초과는 **경고만**(재현성 유지).

능력 판별 한계: OpenAI `/v1/models`는 능력 메타데이터를 주지 않아 vision 여부를 **허용 패턴 allowlist**로
판별한다(버전 관리됨, `RULESET_VERSION`). 새 vision 모델이 예상 못 한 이름으로 나오면 allowlist 갱신
전까지 제외된다 — 알려진 한계.

---

## A3 — API 키 관리 + 콘솔 quota cap (필독)

### 키 주입
- 모든 키는 **gitignore된 `local.properties`**에 둔다(평문 커밋 금지). `local.properties.example`를 복사해 채운다.
- 소비 경로가 키마다 다르다:
  - **Maps**: `AndroidManifest.xml`의 `meta-data`로 주입(`manifestPlaceholders = [MAPS_API_KEY: ...]`).
  - **Places**: 런타임 `Places.initialize(context, KEY)` (Epic E).
  - **Geocoding / Gemini / OpenAI**: REST 호출(`BuildConfig`의 키 사용, Epic D·E).

### 콘솔 quota cap — 도난 키 피해를 제한하는 **유일한** 장치
> 이 앱은 가족용으로 API 키를 APK에 내장한다. 디컴파일로 키 추출이 가능하며, **앱 내 여행당 비용 상한(Epic G)은
> 도난 키를 막지 못한다**(공격자는 앱을 우회해 API를 직접 호출). **도난 키의 비용 피해를 제한하는 것은 각 제공자
> 콘솔의 quota cap뿐이다.** 따라서 콘솔 quota cap을 **필수 안전장치**로 설정한다. 이 잔여 위험은 가족용 범위에서 수용한다.

각 콘솔에서 일/분당 상한을 설정한다(키를 공유하든 분리하든 quota는 API별로 적용된다):

- **Google Cloud (Maps / Places / Geocoding)** — APIs & Services → 각 API → *Quotas & System Limits* →
  "Requests per day" / "Requests per minute"에 상한 입력. 추가로 *Credentials*에서 키에 **API 제한**
  (해당 API만)과 **Android 앱 제한**(패키지명 + SHA-1)을 건다.
- **Google AI Studio / Gemini API** — 프로젝트의 Gemini API 사용량 한도(또는 결제 프로젝트의 quota)를
  일/분 단위로 낮춘다.
- **OpenAI Platform** — *Settings → Limits*에서 **monthly budget / usage limit**과(가능 시) rate limit을
  보수적으로 설정한다.

상한 도달 시 동작과 앱 내 비용 집계는 Epic G에서 구현한다.

---

## 아키텍처 (현재 골격)

- **언어: Java**, **UI: View/XML**. 단일 모듈 + 패키지 레이어 `core` / `domain` / `data` / `di` / `ui`,
  MVVM + Repository, 단방향 흐름.
- AppCompat + Material 3(Views) 테마, **Fragment + Navigation Component**(`res/navigation/nav_graph.xml`) +
  **ViewBinding**. `MainActivity`가 `NavHostFragment`를 호스팅, 3 Fragment(사진 선택 → 분석/진행 → 지도/리플레이).
- DI: **Hilt(annotationProcessor)** — `@HiltAndroidApp`, `@AndroidEntryPoint` Fragment/Activity, `@HiltViewModel`.
- 네트워크/직렬화: **Retrofit + OkHttp + Gson**. 비동기: **ExecutorService**(Epic G). 로컬 저장: **Room(annotationProcessor)**.
- 핵심 인터페이스 `VisionProvider`(Epic D) / `Geocoder`(Epic E) / `TripRepository`(Epic I)는
  빈 스텁으로 Hilt `@Binds` 주입됨 — 각 에픽에서 실제 구현으로 교체.
- Maps **3D SDK(Experimental)** 의존성은 P0 골격을 Preview 아티팩트에 결합하지 않도록 **Epic J(= plan/14-map-3d-flyover)에서 추가**한다.
  (이 README는 에픽을 문자 A/D/E/G/I/J로, `plan/`은 숫자 01–15로 표기한다 — Epic J ↔ plan/14.)

---

## 알려진 제약 (PRD §8 요약)
- GPS 없는 사진 위치는 추정치(도시 단위 가능), 인식 실패 사진은 "위치 미상".
- `READ_MEDIA_IMAGES`는 Play 스토어 정식 배포를 제약(sideload 범위에서 수용).
- Maps 3D는 Experimental — breaking change/GA 과금 위험, 2D로 강등(핵심 가치는 2D로 완결).
- 지도 타일(2D·3D)은 네트워크 필요 — 진정한 오프라인 지도가 아니다.
