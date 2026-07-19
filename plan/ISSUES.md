# TravelTrace — Vertical Slice Issues (tracer bullets)

> `/to-issues` 산출물 초안. 각 항목은 **모든 계층(권한·데이터·네트워크·지도·UI)을 관통하는 얇은 수직 슬라이스**로,
> 그 자체로 데모/검증 가능하다. 기존 `plan/NN-*.md`(수평 에픽)는 각 슬라이스가 참조하는 **역량 사전**이다.
>
> **상태**: 이슈 트래커 미연결 → 파일로 발행. 트래커 연결 시 각 `##` 섹션이 이슈 1개로 1:1 매핑된다.
> 발행 전 사용자 승인 대기(granularity·의존관계 확인).
>
> **진행**: **S0 ✅ 완료** (에픽 01·02 완료, 03은 인자 계약·공유 VM 2항목 미완 — S1에서 함께 처리).
> **다음은 S1.** 에픽별 상세 상태는 [00-overview.md](00-overview.md) 참조.

**의존 그래프**
```
S0 ─ S1 ─┬─ S2 ─ S7
         ├─ S3 ─ S4 ─ S5
         │        └─ S6
         └─ S8
```

**유저 스토리(PRD §3) 커버리지**
| 스토리 | 슬라이스 |
|---|---|
| ① 사진 다중 선택 | S1, S4 |
| ② GPS/시각 읽기 + GPS 없으면 AI | S1(GPS) · S3(AI) · S5(폴백) |
| ③ 지도 핀·경로, AI 근사 위치 구분 | S1 · S3 |
| ④ 재생: 카메라 이동 + 사진 카드 | S2 · S7(3D) |
| ⑤ 일시정지·수동 넘김 | S2 |
| ⑥ "2024 파리 여행" 저장·재생 | S1 · S8 |

---

## S0 — 프리팩터: 앱 골격 & 빌드 척추

### What to build
모든 슬라이스가 얹힐 토대. 단일 Activity + Navigation(XML) 셸, 4개 빈 Fragment(Home/Select/Analyze/Map)와 실제 전환, Hilt DI, Manifest 권한 선언, 시크릿→BuildConfig, **빌드 시점 vision 모델 확정(미검출 시 빌드 실패)**, 디자인 토큰·Pretendard 최소셋, 빈 Room DB. 사용자 가치는 없지만 tracer bullet들의 선행 프리팩터다("make the change easy, then make the easy change").

### Acceptance criteria
- `./gradlew assembleDebug` 성공, 기기에서 Home→Select→Analyze→Map→Home 왕복 전환(stub).
- 유효 vision 모델이 없으면 빌드가 의도적으로 실패.
- API 키가 `local.properties`→BuildConfig로만 주입(하드코딩 0).
- Hilt 그래프·빈 Room DB 정상 초기화, Primary 버튼/카드/폰트가 토큰으로 렌더.

### Blocked by
None — can start immediately.

### Reference
plan/01-project-setup · 02-design-system · 03-app-shell-navigation · 04-data-layer

---

## S1 — GPS 사진 → 시각순 경로 지도 → 저장·재조회

### What to build
가장 얇은 완결 경로. 사용자가 갤러리에서 사진을 다중 선택(권한 포함)하면, **GPS EXIF가 있는 사진만** 촬영 시각(UTC 정규화)순으로 실제 2D Google Map 위에 핀 + 폴리라인으로 그려지고, 그 결과가 "여행"으로 저장되어 Home에서 다시 열린다. **AI·다운스케일 업로드·자동 재생 애니메이션 없음.** GPS 없는 사진은 이번 슬라이스에선 조용히 제외(카운트만).

### Acceptance criteria
- 권한 허용/거부/부분허용 3경로 모두 안내되고 앱이 죽지 않음.
- GPS 있는 사진이 `setRequireOriginal`로 좌표·시각을 실제 추출, UTC 정렬로 핀·경로가 정확.
- 결과가 이름 지정되어 Room에 저장, 앱 재시작 후 Home 목록에 유지·재조회.
- 좌표 없는 사진을 (0,0)에 찍지 않음.

### Blocked by
- S0

### User stories covered
①(부분) · ②(GPS) · ③(GPS 핀/경로) · ⑥(저장/재조회)

### Reference
plan/05-photo-selection · 06-exif-and-time · 12-map-2d(정적) · 15-trip-persistence(저장·Home)

---

## S2 — 경로 리플레이(자동/수동 슬라이드쇼, 2D 카메라)

### What to build
S1의 지도 위에서 **재생**을 누르면 카메라가 스톱을 시각순으로 순회(2D 팬/줌)하며 도착 시 사진 카드를 팝으로 표시하고, 일정 dwell 후 다음으로 이동. 일시정지/재개(이동 중 중단·이어가기), 이전/다음, 타임라인 스크러버 점프, 속도 프리셋, 상영 모드, 지도/위성 토글 포함. 이 슬라이스가 제품의 핵심 가치를 실제로 완성한다.

> 속도 dwell(프로토타입 결정): 느긋이 2800 / 보통 1800 / 빠르게 1100ms.

### Acceptance criteria
- 자동 재생이 시각순 스톱을 순회하며 사진 카드를 팝으로 표시.
- 일시정지→재개가 이동 중에도 자연스럽게 잔여 구간을 이어감.
- 타임라인 점프·이전/다음·속도 프리셋·상영 모드·지도/위성 토글 동작.
- 화면 이탈 시 타이머·애니메이션이 누수 없이 정리.

### Blocked by
- S1

### User stories covered
④ · ⑤

### Reference
plan/13-replay-slideshow · 12-map-2d(토글)

---

## S3 — GPS 없는 사진 → AI 근사 위치 핀(단일 프로바이더)

### What to build
GPS가 없는 사진에 대해 **프라이버시 파이프라인(①로컬 EXIF 읽기 → ②JPEG 재인코딩/다운스케일 → ③업로드)**을 태우고, Gemini(단일)로 랜드마크/도시 *이름*을 구조화 스키마로 얻은 뒤, **Places(POI)/Geocoding(행정 지명) 분기**로 좌표화하여 지도에 **"근사 위치"(점선·반투명) 핀**으로 시각 구분해 표시한다. 동명 지명/저신뢰는 오답 핀 대신 `이름만`으로 강등. AI는 좌표를 만들지 않는다.

### Acceptance criteria
- 업로드 산출물에 GPS/시각 EXIF가 없음(JPEG/HEIC/RAW 입력 모두) 검증.
- "에펠탑"→Places, "파리, 프랑스"→Geocoding 라우팅이 맞고, 성공 시 근사 위치 핀이 GPS 핀과 시각 구분.
- 동명/저신뢰 입력이 `이름만`으로 강등되어 오답 핀이 안 찍힘.
- 파이프라인 순서(①→②→③)가 강제됨.

### Blocked by
- S1

### User stories covered
②(AI) · ③(AI 근사 구분)

### Reference
plan/07-image-upload-pipeline · 08-vision-engine(Gemini) · 09-geocoding

---

## S4 — 배치 분석 강건화: 진행률·동시성·재시도·비용 상한·캐시

### What to build
S1/S3의 단건 처리를 **~100장 실제 배치 흐름**으로 승격. 오케스트레이터가 우선순위(GPS→AI→미상)로 라우팅하고 **동시 4건·타임아웃 30s·재시도 2회(1→2→4s + 지터)·429 백오프**를 강제, **여행당 비용 상한** 집계·중단, **(_ID+콘텐츠 해시) 캐시**로 재분석 회피. Analyze 진행 화면(카운터·현재 인식 텍스트·완료 요약)과 **타임존 확인 시트**를 실제 진행에 바인딩.

### Acceptance criteria
- GPS 사진은 AI 호출 0회, 캐시 hit 시 네트워크 콜 0회(로그 확인).
- 동시 4·타임아웃·재시도·백오프+지터가 실제 적용, 취소 시 진행 중 콜 중단.
- 여행당 상한 도달 시 안전 중단 + 안내, 진행률/완료 요약이 분류 결과와 일치.
- 타임존 시트가 오프셋 부재 시에만 뜨고 결과가 정렬에 반영.

### Blocked by
- S3

### User stories covered
①(스케일) · ②

### Reference
plan/10-analysis-orchestrator · 11-analyze-screen · 04-data-layer(캐시)

---

## S5 — OpenAI 폴백 + 엔진 모드 토글

### What to build
Gemini가 인식 실패/저신뢰일 때 **현행 OpenAI Vision(structured outputs)으로 자동 폴백**하는 듀얼 경로를 `VisionProvider` 뒤에 추가하고, 설정에서 **단일 모델 모드**로 전환하는 토글을 제공한다. 모델 ID는 빌드 시점 확정값(S0) 사용.

### Acceptance criteria
- Gemini 저신뢰/실패 → OpenAI 폴백이 동일 스키마로 자동 발동(듀얼).
- 단일 모드에서 폴백이 발동하지 않음(Trip engineMode 반영).
- 죽은 모델 ID로 나가지 않음, 스키마 위반 응답이 "인식 실패"로 처리.

### Blocked by
- S4

### User stories covered
②(폴백 강건성)

### Reference
plan/08-vision-engine(폴백·모드)

---

## S6 — 위치 미상 / 이름만 / 시각 없음 처리 UI

### What to build
분류 3종을 지도·리플레이에서 올바르게 다룬다. "위치 미상 N" 배지 → drawer(미상 사진 그리드+설명), `이름만`은 이름 노출(향후 수동 핀 대상), `시각 없음`은 핀은 표시하되 경로 순서 끝 그룹. 리플레이 중 핀 **"빼기"(detach)**로 특정 스톱을 위치 미상으로 옮기고 경로 재구성 + 토스트.

### Acceptance criteria
- `PLACED`만 경로에, `이름만`/`미상`은 지도 제외(미상 그룹), `시각없음`은 핀만·순서 제외.
- 위치 미상 배지→drawer 열림, 카운트 정확.
- detach 시 경로·타임라인·인덱스가 일관되게 재계산 + 토스트.

### Blocked by
- S4

### User stories covered
③(부분 정보 처리)

### Reference
plan/13-replay-slideshow(미상·detach) · 04-data-layer(분류 SoT)

---

## S7 — 3D 비행(best-effort) + 강등 계약

### What to build
S2 리플레이 위에 **Maps 3D SDK `flyTo`/`flyAround`** 3D 비행 연출을 옵션으로 얹는다. 초기화 실패/기기·API 미지원/Experimental 종료·GA 과금 전환 감지 시 **자동으로 2D 리플레이로 강등 + 1회 고지**(오류·크래시 금지). 3D 타일 비용 집계(또는 GA 후 기본 OFF) 정책과 sideload 취약 고지 포함.

### Acceptance criteria
- 지원 기기에서 3D 비행이 S2 재생 컨트롤과 연동.
- 미지원/실패/Preview 종료 시 자동 2D 강등 + 1회 고지, 크래시·막힘 없음.
- 3D 없이도(강등 상태) 제품이 완결(S2만으로 완주).
- 비용 정책·sideload 고지가 README·앱에 존재.

### Blocked by
- S2

### User stories covered
④(3D 연출 향상)

### Reference
plan/14-map-3d-flyover

---

## S8 — 저장 여행 오프라인 재생 + 캐시 재사용

### What to build
저장된 여행을 **AI 호출 0회**로 저장 메타데이터(좌표·시각·랜드마크명)만으로 재생한다. 같은 사진은 (_ID+해시) 캐시로 여행을 넘나들며 재사용. **지도 타일은 2D·3D 모두 네트워크 필요**임을 정확히 안내(오프라인 시 빈/실패 타일 위 폴리라인만) — "오프라인 지도" 과장 금지.

### Acceptance criteria
- 저장 여행 열기 → AI 호출 0회로 지도·리플레이 재생(비용 로그 확인).
- 같은 사진 재분석 시 캐시 hit(네트워크 0), 다른 여행에서도 hit.
- 오프라인에서 타일 실패해도 폴리라인 재생 + 한계 안내(크래시 없음).

### Blocked by
- S1

### User stories covered
⑥(재생·오프라인)

### Reference
plan/15-trip-persistence-offline · 04-data-layer(캐시)
