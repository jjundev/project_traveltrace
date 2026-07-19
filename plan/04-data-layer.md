# 04 · 데이터 계층 · Room 스키마 · DAO · 리포지토리 · 캐시 키

> 여행/사진/위치 분석 결과를 담는 Room 스키마와 접근 리포지토리, 그리고 재분석·재호출을 막는 콘텐츠 해시 캐시 키 체계를 만든다.

> **상태: ⬜ 미착수.**

- **Epic**: Data
- **Depends on**: 01
- **PRD**: §4.7 저장·캐싱, §9 #11, §4.6 분류
- **Prototype**: `STOPS()`/`GRIDMETA`(데이터 형상 참고), 홈 여행 카드 메타

## 목표
분석 결과(사진 식별자·좌표·랜드마크명·촬영시각·출처)를 로컬 DB에 여행 단위로 저장하고, 동일 사진의 결과를 캐시로 재사용.

## 범위
- **In**: Room `@Entity`/`@Dao`/`@Database`, 리포지토리, 캐시 키(`MediaStore _ID` + 콘텐츠 해시) 조회/upsert, 마이그레이션 정책, 분류 enum.
- **Out**: 실제 해시 계산 시점(07 파이프라인에서 스트림 중 계산), EXIF/AI 채우기(06·08·10), 지도 렌더(12).

## 스키마 (초안 — 리뷰에서 확정)
- [ ] `Trip`: `id`(PK), `name`("2024 파리 여행"), `coverEmoji`/`region`, `photoCount`, `dayCount`, `startDate`/`endDate`, `timeZoneId`(여행 기준 타임존), `createdAt`, `costSpent`(집계), `engineMode`(dual/single).
- [ ] `Photo`: `id`(PK), `tripId`(FK), `mediaStoreId`(Long), `contentHash`(String), `takenAtUtc`(nullable), `takenAtHasOffset`(bool), `displayName`.
- [ ] `PhotoLocation`: `photoId`(FK/PK), `lat`/`lng`(nullable), `source`(enum: `GPS`/`AI`/`NONE`), `landmarkName`(nullable), `city`/`country`(nullable), `classification`(enum: `PLACED`/`NAME_ONLY`/`UNKNOWN`/`NO_TIME`), `confidence`(nullable), `detached`(bool — 리플레이에서 위치미상으로 뺀 상태).
- [ ] `AnalysisCache`: key=(`mediaStoreId`,`contentHash`), value=(좌표·이름·source·classification·모델·생성시각). **여행과 독립**(사진 재사용 시 여행 넘나들며 캐시 hit).

## 캐시 키 규칙 (§4.7 — 핵심)
- [ ] 캐시/저장 키 = **`MediaStore _ID` + 콘텐츠 해시 조합**. **휘발성 `content://` URI를 영구 키로 쓰지 않는다.**
- [ ] 해시는 **선택 직후 일괄 계산하지 않는다** — 07 분석 파이프라인이 사진 스트림을 읽는 김에 1회 계산(중복 I/O 회피). 04는 그 값을 저장/조회하는 인터페이스만 제공.
- [ ] 캐시는 **기기 로컬 한정**(`_ID`는 기기 내에서만 안정적). 기기 변경/초기화 후 재분석 허용. README에 명시.
- [ ] 캐시 조회 API: `get(mediaStoreId, contentHash)` → hit 시 AI/지오코딩/재해석 스킵.

## 작업 항목
- [ ] Room `@Database`(버전 1), TypeConverter(enum, Instant/UTC millis, lat/lng).
- [ ] DAO: TripDao, PhotoDao, LocationDao, CacheDao (upsert·트랜잭션·여행 삭제 시 cascade 정책).
- [ ] Repository: `TripRepository`(목록/저장/삭제/열기), `AnalysisResultRepository`(사진별 결과 upsert·조회), `CacheRepository`.
- [ ] ExecutorService 기반 비동기 접근(메인 스레드 DB 접근 금지), 콜백/LiveData 반환 규약.
- [ ] 홈 여행 목록 조회(카드 메타: 이름·장수·일수·연월·커버) 쿼리.
- [ ] 빈 상태/트립 없음 구분(홈 empty state, 03·15 연계).

## 완료 조건 (DoD)
- [ ] Trip/Photo/Location/Cache upsert·조회 단위 테스트(인메모리 Room).
- [ ] 캐시 hit/miss 로직이 (`_ID`+해시) 기준으로 동작.
- [ ] 여행 삭제 시 연관 Photo/Location 정리, 캐시는 보존(다른 여행이 참조 가능).
- [ ] 홈 목록 쿼리가 프로토타입 카드 메타를 채울 수 있음.

## 리스크·주의
- 콘텐츠 해시 알고리즘/범위(전체 바이트 vs 앞부분+크기)는 성능·충돌 트레이드오프 — 07과 함께 확정. 결정은 여기 문서에 역기록.
- 다중 타임존 여행은 v1 한계(§8) — 스키마는 여행당 단일 `timeZoneId`만 보관(정밀 처리는 post-MVP).
- `detached`/`classification`을 리플레이 상태(13)와 DB 중 어디를 SoT로 둘지 리뷰에서 확정(저장 여행 재생 시 일관성).
