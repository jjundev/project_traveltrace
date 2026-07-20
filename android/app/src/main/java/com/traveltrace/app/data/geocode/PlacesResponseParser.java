package com.traveltrace.app.data.geocode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Places API (New) Text Search 응답 → 좌표 후보. 랭킹 순서를 보존한다 —
 * {@link com.traveltrace.app.analysis.Disambiguator} 가 1번 후보를 채택하므로
 * 순서가 곧 선택이다.
 *
 * <p>zero-result·형식 이상은 빈 리스트다. 호출자가 그걸 NAME_ONLY 로 강등한다.
 */
public final class PlacesResponseParser {

    private PlacesResponseParser() {}

    public static List<GeoPoint> parse(JsonObject response) {
        List<GeoPoint> points = new ArrayList<>();
        if (response == null || !response.has("places")) {
            return points;
        }
        JsonElement placesElement = response.get("places");
        if (!placesElement.isJsonArray()) {
            return points;
        }
        JsonArray places = placesElement.getAsJsonArray();
        for (int i = 0; i < places.size(); i++) {
            JsonElement entry = places.get(i);
            if (!entry.isJsonObject()) continue;
            JsonObject location = entry.getAsJsonObject().getAsJsonObject("location");
            // 좌표가 없는 항목은 건너뛴다 — 0 으로 메우면 (0,0) 핀이 된다.
            if (location == null || !location.has("latitude") || !location.has("longitude")) {
                continue;
            }
            points.add(new GeoPoint(
                    location.get("latitude").getAsDouble(),
                    location.get("longitude").getAsDouble()));
        }
        return points;
    }
}
