# 08 · AI 인식 엔진 (VisionProvider · Gemini · OpenAI 폴백)

> 사진 → `{landmarkName, city, country, confidence}`를 반환하는 공통 인터페이스와 Gemini(메인)·OpenAI(폴백) 구현. AI는 이름/도시만 반환하고 좌표는 만들지 않는다.

> **상태: ⬜ 미착수.**

- **Epic**: Analysis
- **Depends on**: 01(모델 확정·키), 07(업로드 이미지)
- **PRD**: §4.3, §9 #4·#14
- **Prototype**: `recogName()`("에펠탑 인식 중" 류 진행 텍스트만)

## 목표
사진에서 **랜드마크/도시 이름**을 구조화 스키마로 안정적으로 얻는다. 인식 실패/저신뢰 시 자동 폴백. 좌표화는 09로 넘김.

## 범위
- **In**: `VisionProvider` 인터페이스, `GeminiProvider`, `OpenAiProvider`, 네이티브 스키마 강제, 듀얼 폴백 로직, 단일 모델 모드 토글, 신뢰도 임계값, Retrofit/OkHttp/Gson 매핑.
- **Out**: 좌표 변환(09), 동시성·재시도·타임아웃 정책(10 — 여기선 단건 호출 계약만), 우선순위 결정(10).

## 공통 인터페이스 (§4.3)
- [ ] `VisionProvider`: `VisionResult recognize(byte[]/stream image)` — 반환 `{landmarkName, city, country, confidence}`. **좌표 필드 없음**(AI가 lat/lng를 만들지 못하게 타입으로 차단).
- [ ] 두 구현이 **동일 스키마**로 반환하도록 추상화. 상위(10)는 provider 종류를 모른 채 결과만 사용.

## GeminiProvider (메인)
- [ ] Gemini Vision 호출, **`responseSchema`로 JSON 스키마 강제**(프롬프트 텍스트로만 JSON 유도 금지).
- [ ] 모델 ID는 **01의 BuildConfig 주입값** 사용(하드코딩 금지).
- [ ] 인식 실패/저신뢰 판정 규칙(빈 결과·confidence < 임계값).

## OpenAiProvider (자동 폴백)
- [ ] 현행 OpenAI 멀티모달 Vision, **structured outputs로 스키마 강제**.
- [ ] 모델 ID는 01 BuildConfig 주입값(구 GPT-4o 같은 죽은 ID 금지).
- [ ] Gemini가 실패/저신뢰일 때만 호출(단일 모드 시 비활성).

## 폴백·모드 (§9 #14)
- [ ] 기본 **듀얼**: Gemini → (실패/저신뢰) → OpenAI 재시도.
- [ ] 설정에서 **단일 모델 모드** 토글 → OpenAI 폴백 비활성(Trip `engineMode`, 04).
- [ ] 신뢰도 임계값을 상수/설정으로 관리(09 disambiguation과 함께 튜닝).

## 핵심 결정·제약
- **AI는 이름/도시만.** 좌표는 절대 AI 출력으로 신뢰하지 않음(환각·도시 중심점) — 09에서 지오코딩.
- 스키마는 **네이티브 스키마 강제**(Gemini `responseSchema` / OpenAI structured outputs). Gson은 응답 매핑용.
- 단건 호출은 여기서 **동기적 계약**(입력 이미지 → 결과/예외)만 정의. 병렬·재시도·타임아웃은 10이 감싼다.

## 완료 조건 (DoD)
- [ ] 동일 이미지에 대해 두 provider가 **같은 스키마** 객체를 반환.
- [ ] Gemini 저신뢰/실패 → OpenAI 폴백이 자동 발동(듀얼 모드).
- [ ] 단일 모드에서 폴백이 발동하지 않음.
- [ ] 스키마 위반 응답이 파싱 에러가 아닌 "인식 실패"로 처리됨.
- [ ] 죽은 모델 ID로 나가지 않음(01 연계) 확인.

## 리스크·주의
- 모델 수명 리스크(§8) — 01의 빌드 시점 확정에 의존. 런타임에서 모델 404 시 명확한 실패 처리·로깅.
- 실내/음식/추상 사진은 인식 실패가 정상(→ 위치 미상, 10/§4.6). 실패를 오류로 취급하지 말 것.
- 신뢰도 임계값이 너무 높으면 다수가 폴백/미상으로, 낮으면 오답 핀 위험 — 09 disambiguation과 함께 보정.
