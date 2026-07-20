package com.traveltrace.app.analysis;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.RecognitionResult;

/**
 * 인식 결과 → 어떤 좌표화 API 로 보낼지 (PRD §4.4).
 *
 * <p><b>랜드마크는 Places, 행정 지명은 Geocoding.</b> 방향을 바꾸면 양쪽 다 나빠진다 —
 * Geocoding 은 모호·의미 기반 질의에 약해 "에펠탑"에 zero result 를 내기 쉽고, Places 는
 * "프랑스" 같은 넓은 행정 지명에 엉뚱한 상호를 매칭한다.
 *
 * <p>이 클래스는 <b>신뢰도를 보지 않는다.</b> 저신뢰 강등은 이름을 보존한 채 NAME_ONLY 로
 * 내리는 별개 결정이고 {@link AiLocationResolver} 가 소유한다. 여기서 저신뢰에 null 을
 * 돌려주면 "이름만"과 "위치 미상"의 구분이 사라진다(PRD §4.6).
 */
public final class GeocodeRouter {

    /**
     * 좌표를 찍어도 되는 최소 신뢰도. 너무 높이면 대부분이 NAME_ONLY 로 떨어져 AI 를 붙인
     * 의미가 없어지고, 너무 낮추면 오답 핀이 는다. {@link Disambiguator} 가 2차 방어선을
     * 맡고 있으므로 여기서는 명백한 추측만 걸러내는 수준으로 둔다.
     */
    public static final double MIN_CONFIDENCE = 0.55d;

    private GeocodeRouter() {}

    /** 좌표화할 이름이 없으면 null — 호출자는 이를 "위치 미상"으로 다룬다. */
    @Nullable
    public static GeocodeQuery queryFor(RecognitionResult result) {
        String landmark = trimToNull(result.landmarkName);
        if (landmark != null) {
            // 도시 중심점보다 항상 구체적이므로 도시/국가가 함께 와도 랜드마크가 이긴다.
            return new GeocodeQuery.Poi(landmark);
        }

        String city = trimToNull(result.city);
        String country = trimToNull(result.country);
        if (city != null && country != null) {
            return new GeocodeQuery.AdministrativePlace(city + ", " + country);
        }
        if (city != null) {
            return new GeocodeQuery.AdministrativePlace(city);
        }
        if (country != null) {
            return new GeocodeQuery.AdministrativePlace(country);
        }
        return null;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
