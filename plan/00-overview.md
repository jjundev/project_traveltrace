# 00 · TravelTrace 구현 계획 개요 (Master Index)

> 갤러리 여행 사진 → 위치 인식 → 지도 위 촬영 시각순 경로 리플레이 Android 앱.
> 이 폴더의 각 `NN-*.md`는 하나의 **에픽(구현 단계)**이며, 에픽마다
> `grill-yourself → grill-review deep(blocker 0까지) → 구현` 워크플로를 밟는다.

- **참조 문서**: [PRD.md](../PRD.md), 프로토타입 [TravelTrace.dc.html](../prototype/project/TravelTrace.dc.html)
- **스택 요약**: Android(Java, View/XML), Fragment + Navigation Component + ViewBinding,
  ExecutorService(비동기), Retrofit/OkHttp + Gson, Room(annotationProcessor), Hilt(annotationProcessor),
  Gradle Groovy DSL + buildSrc(Java).
- **핵심 가치**: 촬영 시각순 **2D 경로 리플레이**가 제품의 본질. 3D 비행은 best-effort 부가 기능.

---

## 에픽 목록 & 의존 순서

| # | 파일 | 에픽 | 선행 의존 | PRD 매핑 |
|---|------|------|-----------|----------|
| 01 | [project-setup](01-project-setup.md) | 프로젝트 스캐폴딩·빌드·키·DI | — | §6, §4.3, §5 보안 |
| 02 | [design-system](02-design-system.md) | 디자인 토큰·폰트·공용 컴포넌트 | 01 | 프로토타입 전체 |
| 03 | [app-shell-navigation](03-app-shell-navigation.md) | Navigation graph·Fragment 골격 | 01, 02 | §3, 프로토타입 화면 4종 |
| 04 | [data-layer](04-data-layer.md) | Room 스키마·DAO·리포지토리·캐시 키 | 01 | §4.7, §9-11 |
| 05 | [photo-selection](05-photo-selection.md) | 권한·MediaStore·선택 그리드 UI | 02, 03, 04 | §4.1, 프로토타입 SELECT |
| 06 | [exif-and-time](06-exif-and-time.md) | EXIF GPS/시각 추출·UTC 정규화·타임존 | 04, 05 | §4.2, §8 |
| 07 | [image-upload-pipeline](07-image-upload-pipeline.md) | 프라이버시 파이프라인·다운스케일·해시 | 04, 06 | §5 프라이버시, §4.7 |
| 08 | [vision-engine](08-vision-engine.md) | VisionProvider·Gemini·OpenAI 폴백 | 01, 07 | §4.3 |
| 09 | [geocoding](09-geocoding.md) | Places/Geocoding 분기·disambiguation | 08 | §4.4 좌표화 |
| 10 | [analysis-orchestrator](10-analysis-orchestrator.md) | 우선순위 파이프라인·동시성·재시도·비용 상한·분류 | 06, 07, 08, 09 | §4.2, §4.6, §4.8 |
| 11 | [analyze-screen](11-analyze-screen.md) | 분석 진행 화면·타임존 시트·완료 전환 | 03, 10 | §4.8, 프로토타입 ANALYZE |
| 12 | [map-2d](12-map-2d.md) | 표준 Maps SDK·핀·경로·지도/위성 토글 | 03, 04, 10 | §4.4, 프로토타입 MAP |
| 13 | [replay-slideshow](13-replay-slideshow.md) | 카메라 리플레이·컨트롤·타임라인·상영모드·위치미상 | 12 | §4.5, §4.6, 프로토타입 MAP |
| 14 | [map-3d-flyover](14-map-3d-flyover.md) | Maps 3D SDK 비행·강등 계약 | 12, 13 | §4.4 강등 계약 |
| 15 | [trip-persistence-offline](15-trip-persistence-offline.md) | 여행 저장·캐싱·오프라인 재생 | 04, 10, 12 | §4.7, §5 오프라인 |

```
01 ─┬─ 02 ─ 03 ─┬─ 05 ─ 06 ─ 07 ─ 08 ─ 09 ─ 10 ─┬─ 11
    │            │                                │
    └─ 04 ───────┴────────────────────────────────┼─ 12 ─ 13 ─ 14
                                                   └─ 15
```

---

## 마일스톤

- **M1 — 걷는 골격 (Walking skeleton)**: 01~05. 앱 실행 → 권한 → 사진 선택 그리드까지. 지도/분석은 stub.
- **M2 — 분석 파이프라인 동작**: 06~11. 선택한 사진이 좌표·시각으로 해석되어 DB에 저장, 진행률 표시.
- **M3 — 핵심 가치 완성 (필수)**: 12~13. 2D 지도 위 핀·경로·리플레이. **여기까지가 제품 완결선.**
- **M4 — 부가·정리**: 14~15. 3D 비행(best-effort), 저장·오프라인 재생 마감.

> **강등 계약(#3)**: 12~13(2D)만으로 MVP가 완결되어야 하며, 14(3D)는 실패해도 정상 강등이어야 한다.
> 14를 M3에 끼워 넣지 말 것 — 3D 미가용은 오류가 아니다.

---

## 프로토타입 화면 ↔ 에픽 매핑

| 프로토타입 화면 (`screen` state) | 구현 에픽 |
|---|---|
| `home` (여행 목록 / empty / 새 여행 CTA) | 03, 04, 15 |
| `select` (기간 카드 + 3열 선택 그리드 + 분석 시작) | 05 |
| `analyze` (진행 스피너·진행률·타임존 시트·완료 CTA) | 11 |
| `map` (공용 지도 레이어·상단바·하단시트·리플레이·상영모드·위치미상 drawer) | 12, 13, 14 |

> 프로토타입의 카메라 합성(`applyLayer`/`startFlight`/rAF swoop)은 **HTML/CSS 3D 흉내**다.
> 네이티브에서는 12(표준 Maps 카메라 애니메이션)·14(Maps 3D `flyTo`)로 재현한다 —
> 프로토타입의 CSS transform 코드를 그대로 옮기지 말고 **연출 의도(줌아웃 아크·tilt·도착 팝)**만 재현한다.

---

## 공통 완료 정의 (Definition of Done, 모든 에픽 공통)

- [ ] 해당 에픽의 PRD 결정(§9 Decision Log)과 어긋나지 않는다.
- [ ] Java + View/XML 원칙 준수 (Kotlin/Compose 도입 금지).
- [ ] 비동기는 ExecutorService 기반, JSON은 Gson, DI는 Hilt(annotationProcessor).
- [ ] 신규 위험/미결 항목은 해당 파일 "리스크" 절에 기록.
- [ ] 사용자 대면 문자열은 한국어 리소스(`strings.xml`)로 분리.

## 용어 (Glossary)

- **위치 출처(src)**: `gps`(EXIF GPS) / `ai`(AI 이름 추론 + 지오코딩, "근사 위치").
- **분류 3종**: `위치 미상`(GPS·AI 모두 실패) / `이름만`(지오코딩·disambiguation 실패) / `시각 없음`(좌표 O, 촬영시각 X).
- **VisionProvider**: Gemini/OpenAI를 공통 인터페이스 뒤에 둔 추상화. 이름/도시만 반환, 좌표는 만들지 않음.
- **강등(Degradation)**: 3D 불가 시 자동으로 2D 리플레이로 전환하는 정상 동작.
