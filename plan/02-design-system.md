# 02 · 디자인 시스템 · 폰트 · 공용 컴포넌트

> 프로토타입의 Toss 계열 토큰(색·타이포·간격)과 Pretendard 폰트를 Android 리소스로 이식하고, 화면들이 재사용할 공용 UI 요소를 만든다.

- **Epic**: Foundation
- **Depends on**: 01
- **PRD**: 프로토타입 전체 (시각 스펙)
- **Prototype**: `_ds/.../tokens/*.css`, `TravelTrace.dc.html`(인라인 스타일 전반)

## 목표
프로토타입을 **픽셀에 가깝게** 재현할 수 있는 토큰·테마·폰트·컴포넌트 기반. 이후 모든 화면 에픽이 raw 값 대신 이 토큰을 참조.

## 범위
- **In**: color/dimens/typography 리소스, Pretendard 폰트, 라운드 카드·Primary/Neutral 버튼·pill/badge·바텀시트 핸들·그라데이션 CTA 배경 등 공용 스타일·drawable, 라이트 테마.
- **Out**: 각 화면 조립(03~), 지도/애니메이션(12~).

## 토큰 이식 (출처: `tokens/colors.css`, `typography.css`)
- [ ] `colors.xml`: 브랜드/그레이/시맨틱 별칭. **핵심만 발췌** — 여행 앱은 증권용 up/down(빨강/파랑) 시맨틱은 **불필요**하니 이식하지 않는다. 실제 사용값:
  - `toss_blue #3182f6`(fill-brand), `fill-brand-hover #2272eb`, `fill-brand-pressed #1b64da`, `fill-brand-weak rgba(49,130,246,.09)`
  - 텍스트: `text-primary #191f28`, `text-secondary #4e5968`, `text-tertiary #6b7684`, `text-disabled #b0b8c1`
  - 표면/배경: `bg-screen #f6f7f9`, `bg-base #f2f4f6`, `surface #ffffff`, `overlay-dim rgba(0,0,0,.20)`
  - 그레이 스케일 `grey-100~300`, `green-500 #03b26c`(완료 체크), `yellow-400 #ffd158`(위치미상 아이콘)
  - AI 근사 위치 표식용 `grey-500 #8b95a1`(점선/반투명 핀)
- [ ] `dimens.xml`: 타입 스케일(30/24/20/17/15/14/13/12/10sp)과 공용 radius(11·12·14·18·22dp), spacing.
- [ ] 폰트: **Pretendard**(woff2 → Android용 `.ttf`/`.otf` 확보 또는 `fonts.css`가 참조하는 원본으로 대체). `res/font/` 등록 + `font-family` 스타일. Tossface 이모지는 시스템 이모지로 대체(🇫🇷🌋 등은 텍스트 이모지로 렌더).
- [ ] 타이포 스타일(`styles.xml` `TextAppearance.*`): Title/Body/Label/Caption. 굵기 700 헤딩, 500/600 본문 기조, `letter-spacing -0.02em`, 숫자 tabular(가능 범위).

## 공용 컴포넌트·drawable
- [ ] **Primary 버튼**: height 54dp, radius 14dp, `fill-brand`, 흰 텍스트 16sp/700, 그림자(`0 6px 20px rgba(49,130,246,.32)`). press 상태 색 전환.
- [ ] **Neutral 버튼**: `fill-neutral` 배경 + 브랜드/세컨더리 텍스트.
- [ ] **카드**: radius 18dp, `surface`, `shadow-sm`. 여행 카드(썸네일 헤더 + 제목 + 메타 라인).
- [ ] **Pill/Badge**: 위치 라벨(반투명 blur 배경), `GPS` 배지(파랑), `근사 위치` 배지(회색 + 점선 테두리), `위치 미상 N` 배지(다크 반투명 + 노란 아이콘).
- [ ] **바텀시트 셸**: 상단 radius 22dp, 40×4 핸들 바, `overlay-dim` 스크림.
- [ ] **CTA 그라데이션 배경**: 하단 fade(`to top, bg-screen 62%→투명`) — 플로팅 버튼 뒤 처리.
- [ ] 스피너(분석), 체크 아이콘(완료), 아이콘셋(뒤로/설정/캘린더/핀/재생·일시정지·이전·다음/정보) drawable(vector).

## 핵심 결정·제약
- 프로토타입 인라인 스타일의 **시각 결과**만 재현. HTML 구조·`sc-if`/`sc-for` 로직은 이식 대상 아님(03~에서 View로 재설계).
- 390×844 기준 프로토타입 → dp 매핑 시 밀도 독립성 확보(하드 px 금지).
- 다크모드는 v1 범위 밖(라이트 고정). 위성 뷰에서 상태바 흰색 처리 등 화면 국소 대비는 12에서 처리.

## 완료 조건 (DoD)
- [ ] 토큰만으로 Primary 버튼·여행 카드·배지·바텀시트를 샘플 화면에서 렌더해 프로토타입과 대조.
- [ ] Pretendard가 실제 적용됨(폴백 폰트 아님) 확인.
- [ ] 색/치수/타이포가 raw 값이 아닌 리소스 참조로 사용되는 규칙 확립.

## 리스크·주의
- Pretendard 라이선스/포맷: woff2는 Android 미지원 → ttf/otf 확보 필요. 라이선스(OFL) 준수.
- 증권 DS에서 온 토큰 중 **여행 앱에 무의미한 것(시장 up/down)**을 그대로 들이지 말 것 — 혼선 방지.
