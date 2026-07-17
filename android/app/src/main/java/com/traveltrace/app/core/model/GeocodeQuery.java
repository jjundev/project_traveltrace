package com.traveltrace.app.core.model;

/**
 * 지오코딩 입력 분기 (PRD §4.4):
 *  - {@link Poi}: 랜드마크/POI 이름 → Places API (Text Search)
 *  - {@link AdministrativePlace}: 도시/국가 등 행정 지명 → Geocoding API
 */
public abstract class GeocodeQuery {
    private GeocodeQuery() {}

    public static final class Poi extends GeocodeQuery {
        public final String name;

        public Poi(String name) {
            this.name = name;
        }
    }

    public static final class AdministrativePlace extends GeocodeQuery {
        public final String text;

        public AdministrativePlace(String text) {
            this.text = text;
        }
    }
}
