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
