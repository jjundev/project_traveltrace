# 09 · 좌표화 (Places / Geocoding 분기 · disambiguation)

> AI가 반환한 이름/도시를 좌표로 변환한다. 입력 유형에 따라 Places(POI)와 Geocoding(행정 지명)을 분기하고, 모호하면 "이름만"으로 강등한다.

- **Epic**: Analysis
- **Depends on**: 08
- **PRD**: §4.4 좌표화 분기, §4.3 disambiguation, §9 #7
- **Prototype**: AI 핀(`src:'ai'`, 점선/반투명 "근사 위치")

## 목표
AI 이름/도시 → `{lat, lng}`를 **적절한 API로** 얻고, 신뢰할 수 없으면 확신에 찬 오답 핀 대신 "이름만"으로 분류.

## 범위
- **In**: 좌표화 분기 라우팅, Places Text Search 호출, Geocoding 호출, disambiguation 규칙, 결과 → `PhotoLocation`(source=AI).
- **Out**: AI 인식(08), 병렬·재시도(10), 지도 렌더(12).

## 좌표화 분기 (§4.4 — 핵심)
- [ ] **랜드마크/POI 이름**(예: "에펠탑") → **Places API (Text Search)**. 모호·의미 기반 질의에 강함.
- [ ] **도시/국가 등 행정 지명 문자열** → **Geocoding API**. (Geocoding은 모호 질의에 약해 zero result 가능 → **POI 이름엔 쓰지 않는다.**)
- [ ] 08 결과에서 `landmarkName` 유무로 라우팅: landmark 있으면 Places, 도시/국가만 있으면 Geocoding.

## disambiguation (§4.3 — 핵심)
- [ ] 지오코딩 결과가 **다수(동명 지명)**이거나 **신뢰도 임계값 미만**이면 해당 사진을 **`NAME_ONLY`(이름만, 위치 미상과 별도)**로 분류(§4.6). → 확신에 찬 오답 핀 방지.
- [ ] 성공(단일·고신뢰) → `source=AI`, 좌표 저장, 지도에서 **"근사 위치"로 시각 구분**(점선/반투명 핀 — 12).
- [ ] 분류 결과를 04 `PhotoLocation.classification`에 기록.

## 작업 항목
- [ ] Places/Geocoding Retrofit 서비스 + Gson 매핑. 키는 01 BuildConfig.
- [ ] 라우터: `GeocoderRouter.resolve(VisionResult) → GeoResult(PLACED | NAME_ONLY)`.
- [ ] 결과 다수/저신뢰 판정 기준 상수화(08 임계값과 함께 튜닝).
- [ ] 실패/zero-result → `NAME_ONLY` (이름 보존), 예외는 재시도 대상(10)으로 전파.

## 핵심 결정·제약
- **AI 좌표 불신**(§4.2-2, #4): 좌표는 오직 Places/Geocoding 산출물. AI의 lat/lng는 존재해도 무시.
- 입력 유형별 API 선택은 정확도의 핵심 — POI에 Geocoding, 도시에 Places를 쓰지 않도록 라우팅 테스트.
- "이름만"은 위치 미상과 **별개 분류**(이름 노출, 향후 수동 핀 대상 — §4.6).

## 완료 조건 (DoD)
- [ ] "에펠탑" → Places로 파리 좌표, "파리, 프랑스" → Geocoding으로 도시 좌표.
- [ ] 동명 지명/저신뢰 입력이 `NAME_ONLY`로 강등되고 오답 핀이 찍히지 않음.
- [ ] source=AI 결과가 12에서 "근사 위치"로 구분 렌더될 데이터를 갖춤.
- [ ] zero-result·에러가 각각 `NAME_ONLY`·재시도로 올바르게 분기.

## 리스크·주의
- Places vs Geocoding 라우팅 오분류 시 zero-result 급증 — 08 출력 필드 신뢰도에 민감. 테스트 코퍼스로 검증.
- 도시 단위 정확도 한계(§8) — "근사 위치" 라벨링으로 사용자 기대 관리.
- Places/Geocoding 비용은 10 여행당 비용 상한에 포함(콜당 과금).
