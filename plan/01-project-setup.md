# 01 · 프로젝트 스캐폴딩 · 빌드 · 키 · DI

> 빈 저장소에서 컴파일·실행되는 Android(Java/View) 앱 골격과 빌드 인프라, 시크릿 관리, 빌드 시점 비전 모델 확정 로직을 세운다.

> **상태: ✅ 완료.** DoD 4항목 충족. 단 `gradle/visionModels.generated.json`이 플레이스홀더 시드
> (`resolvedAtIso: 2026-01-01`)라 `./gradlew check`가 staleness **경고**를 낸다 — 실제 API 호출 전
> `./gradlew resolveVisionModels` 1회 필요(GEMINI/OPENAI 키 + 네트워크).

- **Epic**: Foundation
- **Depends on**: — (최상위)
- **PRD**: §6 기술 스택, §4.3(빌드 시점 모델 확정), §5 보안, §9 #5
- **Prototype**: 없음 (인프라)

## 목표
빈 프로젝트 → `./gradlew assembleDebug` 성공 → 기기에서 빈 MainActivity 실행. 이후 모든 에픽이 얹힐 토대.

## 범위
- **In**: Gradle(Groovy) + buildSrc(Java), 의존성 카탈로그, Hilt, Manifest·권한 선언, 시크릿→BuildConfig, 빌드 시점 vision 모델 확정, 모듈/패키지 구조, 최소 CI(선택).
- **Out**: 실제 UI/화면(03), 네트워크 클라이언트 구현(08에서 Retrofit 인스턴스), Room 스키마(04).

## 작업 항목
- [x] `settings.gradle`(Groovy) + 루트/`app` 모듈 `build.gradle`(Groovy DSL). **Kotlin DSL 사용 금지.**
- [x] `buildSrc`를 **Java**로 구성 (버전·의존성 상수, 빌드 시점 모델 확정 태스크 위치).
- [x] `compileSdk`/`targetSdk` 최신, `minSdk`는 `READ_MEDIA_IMAGES`(API 33) + `setRequireOriginal`/`ACCESS_MEDIA_LOCATION` 고려하여 결정하고 근거 기록. (하한 후보와 트레이드오프를 리뷰에서 확정.)
- [x] 의존성: Retrofit/OkHttp, Gson(converter-gson), Room(runtime + `annotationProcessor`), Hilt(`hilt-android` + `annotationProcessor`), Maps SDK, Places SDK, exifinterface, lifecycle, navigation-fragment/ui. **KSP·kotlinx.serialization·코루틴 금지.** (Maps 3D SDK(Experimental)는 P0 골격을 Preview 아티팩트에 결합하지 않도록 **의존성 추가·사용 모두 Epic 14로 이연** — 여기선 카탈로그에 넣지 않는다.)
- [x] `Application` 클래스에 `@HiltAndroidApp`, 빈 `MainActivity`(`@AndroidEntryPoint`).
- [x] `AndroidManifest.xml` 권한: `READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`. (위치 권한(`ACCESS_FINE_LOCATION`)은 불필요 — 사진 EXIF만 사용.)
- [x] 패키지 구조 확정. 실제 채택: `core/model`(도메인 모델), `domain`(VisionProvider/Geocoder/TripRepository 인터페이스), `data`(구현·stub), `di`, `ui/`(하위에 `photo`/`analysis`/`map`). 분석 로직 패키지(`analysis/`의 vision·geocode·pipeline)와 `util/`은 해당 로직이 등장하는 후속 에픽(08~10 등)에서 추가한다.
- [x] 코드 스타일/린트(선택), `.gitignore`(`local.properties`, 키, 빌드 산출물).

## 시크릿 관리 (§5, §9 #5)
- [x] API 키(Gemini, OpenAI, Google Maps/Places)는 `local.properties`(VCS 제외) → `buildConfigField`로 주입. 코드/리소스에 평문 하드코딩 금지.
- [x] Maps/Places API 키는 Manifest `meta-data` 주입 방식과 일치시킴(`manifestPlaceholders`).
- [x] **README/앱에 명시**: 앱 내장 키는 디컴파일로 추출 가능하며, **도난 키 비용 피해는 콘솔 quota cap으로만 제한**된다(앱측 상한은 방어가 아님). 가족용 범위에서 수용.
- [x] 각 API에 대해 **콘솔측 quota cap을 필수 안전장치로** 설정하도록 README에 셋업 절차 문서화.

## 빌드 시점 비전 모델 확정 (§4.3 — 핵심)
> **채택 아키텍처: 조회와 빌드를 분리한다.** (매 빌드가 live를 조회하지 않는다 — 재현성·오프라인 확보.)
- [x] 비전 모델 ID를 소스에 **하드코딩하지 않는다**. 명시적 태스크 `resolveVisionModels`(buildSrc, 수동/CI)가 Gemini/OpenAI의 live 모델 목록을 조회해 적합 vision 모델을 고르고, 그 결과를 **VCS에 커밋되는 `gradle/visionModels.generated.json`**에 기록한다.
- [x] **일반 빌드**는 이 커밋 파일을 configuration 단계에서 읽어 `BuildConfig.{GEMINI,OPENAI}_VISION_MODEL`로 주입한다. 네트워크·API 키 불필요 → **오프라인·재현 가능**. (커밋 파일 자체가 §36이 요구하던 "오프라인 폴백"을 충족한다.)
- [x] **적합 모델을 못 찾으면 하드 실패**: `resolveVisionModels`는 `NoSuitableModelException`→`GradleException`을, 일반 빌드는 파일 부재/빈 ID 시 configuration `GradleException`을 낸다. (구 GPT-4o처럼 폐기된 ID로 나가는 것을 원천 차단. 키 부재 에러는 "no suitable model"과 구분.)
- [x] 유효성 검증 규칙: `modelIdGuard`(소스 하드코딩 ID 금지) + `visionModelStalenessGuard`(ruleset 버전 불일치=하드 실패, 경과일 초과=경고)를 `check` 라이프사이클에 연결. configuration 단계 read/validate 로직은 buildSrc `VisionModelsFile`로 추출(app/build.gradle 인라인 중복 제거·단일 출처)하고, 부재/빈 ID 하드 실패를 `VisionModelsFile` 단위테스트로 검증(DoD 참조).

## 핵심 결정·제약
- Java + View/XML, Groovy DSL, annotationProcessor 고정. 이 원칙은 전 에픽 공통(00 DoD).
- 비동기 표준: `ExecutorService`(고정 스레드풀). 이후 에픽에서 동시성 상한(4)·타임아웃(30s)·재시도(2)는 10에서 정책화.

## 완료 조건 (DoD)
- [x] `./gradlew assembleDebug` 성공, 기기/에뮬에서 빈 화면 앱 실행.
- [x] 키 없이 clone한 사람이 `local.properties` 템플릿만 채우면 빌드되는 문서화.
- [x] 유효 vision 모델이 없을 때 빌드가 **의도적으로 실패**함을 검증 (buildSrc `VisionModelsFileTest`: 부재/빈 ID `visionModels.generated.json`에서 `InvalidVisionModelsFileException`이 던져져 configuration이 중단됨을 단언 — app/build.gradle이 실제 실행하는 코드 경로).
- [x] Hilt 그래프가 빈 상태로 정상 초기화.

## 리스크·주의
- Maps 3D SDK(Experimental)는 저장소·좌표 미해결일 수 있음 — 의존성만 추가하고 실제 사용은 14로 미룸.
- 빌드 시점 모델 조회가 CI/오프라인에서 실패하지 않도록 폴백 목록 관리 필요.
- `minSdk`를 너무 낮게 잡으면 미디어 권한 분기 폭증 — 33+ 채택 시 근거·영향 기록.
