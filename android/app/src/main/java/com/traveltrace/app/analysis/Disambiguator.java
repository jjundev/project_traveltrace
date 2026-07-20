package com.traveltrace.app.analysis;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.List;

/**
 * 좌표 후보 다수 → 채택할 1개, 또는 "판단 보류"(null) (PRD §4.3 disambiguation).
 *
 * <p>판정 기준은 <b>개수가 아니라 흩어짐</b>이다. plan/09 의 "결과가 다수면 강등"을
 * 문자 그대로 구현하면 Places 가 인접 시설을 함께 돌려주는 정상 응답까지 전부 강등되어
 * AI 경로가 사실상 죽는다. 대신 상위 후보를 기준으로 나머지가 {@link #AMBIGUITY_RADIUS_KM}
 * 안에 있는지 본다 — 한 동네에 모여 있으면 같은 장소, 대륙을 건너면 동명 지명이다.
 *
 * <p>보류(null)의 대가는 "이름만" 표시이고, 오판의 대가는 <em>지도 위의 확신에 찬 오답
 * 핀</em>이다. 후자가 훨씬 나쁘므로 애매하면 보류한다.
 */
public final class Disambiguator {

    /**
     * 같은 장소로 볼 최대 반경(km). 대도시 하나가 통째로 들어가되 인접 도시는 갈라질
     * 크기다 — 파리 시내 전역은 묶이고 파리(텍사스)는 갈린다.
     */
    public static final double AMBIGUITY_RADIUS_KM = 50d;

    private static final double EARTH_RADIUS_KM = 6371.0088d;

    private Disambiguator() {}

    @Nullable
    public static GeoPoint resolve(List<GeoPoint> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        GeoPoint top = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            if (distanceKm(top, candidates.get(i)) > AMBIGUITY_RADIUS_KM) {
                return null;   // 동명 지명 — 찍지 않는다.
            }
        }
        return top;
    }

    /**
     * 하버사인. 경도 뺄셈으로 근사하면 날짜변경선 근처에서 22km 를 39000km 로 재어
     * 멀쩡한 인식을 강등시킨다.
     */
    static double distanceKm(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.lng - a.lng);

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1d, Math.sqrt(h)));
    }
}
