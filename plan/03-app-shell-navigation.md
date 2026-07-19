# 03 · 앱 셸 · Navigation Graph · Fragment 골격

> 단일 Activity + Navigation Component(XML nav graph) + Fragment 4종의 골격과 화면 전환을 세운다. 각 화면은 stub이되 전환은 실제로 동작.

> **상태: 🟡 거의 완료 — 2항목 미완.** 전환·백스택은 동작하나 **인자 계약**과 **공유 ViewModel**이 없다.
> 둘 다 넘길 데이터가 없어서(04 미착수) 못 만든 것이며, **S1에서 04와 함께 반드시 처리**해야 한다.
> 이 파일 "리스크" 절의 경고 — 여기서 경계를 잘못 잡으면 10·13에서 리플레이 상태 관리가 꼬인다 — 가 그대로 유효하다.

- **Epic**: Foundation
- **Depends on**: 01, 02
- **PRD**: §3 사용자 시나리오
- **Prototype**: `screen` 상태 4종(home/select/analyze/map)과 그 사이 전환(`onNewTrip`/`startAnalyze`/`gotoMap`/`goHome`/`openTrip`)

## 목표
프로토타입의 화면 전이 그래프를 네이티브 Navigation으로 구현. Home → Select → Analyze → Map → (Home) 왕복이 실제로 동작하는 걷는 골격.

## 범위
- **In**: `MainActivity`(NavHost 호스트), `nav_graph.xml`, 4개 Fragment(Home/Select/Analyze/Map) 골격, ViewBinding, 인자 전달(Safe Args 또는 Bundle), 백스택·뒤로가기 정책.
- **Out**: 각 화면 내부 기능(05·11·12~), 데이터 로딩(04~).

## 화면·전환 매핑 (프로토타입 → nav)
| 프로토타입 액션 | 전환 |
|---|---|
| `onNewTrip` (홈 CTA) | Home → Select |
| `startAnalyze` (분석 시작) | Select → Analyze (+타임존 시트) |
| `gotoMap` (지도에서 보기) | Analyze → Map |
| `openTrip` (여행 카드 탭) | Home → Map (저장된 여행 직접 열기) |
| `goHome` | any → Home (백스택 정리) |
| `backToSelect` | Analyze → Select |

## 작업 항목
- [x] `MainActivity` + `NavHostFragment`. `@AndroidEntryPoint`.
- [x] `nav_graph.xml`: 4 destination + action. 시작 destination = Home.
- [x] 4 Fragment 골격(ViewBinding, 빈 레이아웃 + 임시 라벨). Hilt `@AndroidEntryPoint`.
- [ ] 인자 계약 정의: Select→Analyze(선택 사진 식별자 목록·타임존), Analyze→Map(tripId), Home→Map(tripId). **대용량 사진 목록은 Bundle에 직접 싣지 말고** 공유 ViewModel/리포지토리 핸들로 전달(04 연계). ← **미완**: `nav_graph.xml`에 `<argument>` 0개, Safe Args 미도입. 04 대기.
- [x] 뒤로가기 정책: Map/Analyze에서 뒤로 → Home로 백스택 pop, 진행 중 타이머·분석 취소 훅(11·10 연계) 지점 마련.
- [ ] 화면별 공유 상태 보관용 ViewModel 스캐폴딩(트립 컨텍스트). ← **미완**: ViewModel 4개가 전부 화면 전용. 04 대기.
- [x] 시스템 인셋/상태바 처리(프로토타입의 커스텀 상태바 UI는 이식하지 않고 실제 시스템 상태바 사용).

## 핵심 결정·제약
- **단일 Activity + Fragment** (PRD §6). Compose Navigation 아님, XML nav graph.
- 화면 간 대용량 데이터(선택 사진 URI/ID 수백 개)는 인자로 직렬화하지 말고 리포지토리/공유 VM 경유(성능·TransactionTooLarge 방지).
- 프로토타입의 "공용 지도 레이어(analyze/map 공유)"는 네이티브에선 Map 관련 로직을 12·13에 두고, Analyze는 별도 Fragment로 분리(프로토타입의 mapVisible 공유는 CSS 편의였을 뿐).

## 완료 조건 (DoD)
- [x] Home→Select→Analyze→Map→Home 왕복이 stub 상태로 실제 전환됨.
- [x] 여행 카드 탭 → Map 직접 진입 경로 동작(더미 tripId).
- [x] 뒤로가기 시 백스택·리소스 정리 훅이 호출됨(로그로 확인).
- [x] 회전/프로세스 재생성 시 현재 화면 복원(기본 수준).

## 리스크·주의
- 프로토타입은 상태 하나로 모든 화면을 그리는 SPA식 — 네이티브 Fragment 경계로 재설계하며 상태 공유 지점을 명확히(공유 VM). 여기서 경계를 잘못 잡으면 10·13에서 리플레이 상태 관리가 꼬임.
- Analyze는 취소 가능해야 함(사용자가 뒤로 나가면 진행 중 API 콜 중단) — 훅만 마련하고 실제 취소는 10·11.
