package com.traveltrace.app.data.geocode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.traveltrace.app.core.model.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Geocoding API 응답 → 좌표 후보.
 *
 * <p>Geocoding 은 HTTP 200 에 실패를 실어 보낸다 — {@code status} 가 OK 가 아니면
 * {@code results} 를 읽지 않는다. 이 확인을 빼먹으면 REQUEST_DENIED 응답에서 부분/낡은
 * 배열을 읽어 엉뚱한 핀을 찍는다.
 *
 * <p>후보를 <b>자르지 않는다</b> — 전부 넘겨야 Disambiguator 가 동명 지명을 판정한다.
 */
public final class GeocodingResponseParser {

    private static final String STATUS_OK = "OK";

    private GeocodingResponseParser() {}

    public static List<GeoPoint> parse(JsonObject response) {
        List<GeoPoint> points = new ArrayList<>();
        if (response == null || !response.has("status")) {
            return points;
        }
        if (!STATUS_OK.equals(response.get("status").getAsString())) {
            return points;   // ZERO_RESULTS·REQUEST_DENIED·OVER_QUERY_LIMIT 등.
        }
        JsonElement resultsElement = response.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return points;
        }
        JsonArray results = resultsElement.getAsJsonArray();
        for (int i = 0; i < results.size(); i++) {
            JsonElement entry = results.get(i);
            if (!entry.isJsonObject()) continue;
            JsonObject geometry = entry.getAsJsonObject().getAsJsonObject("geometry");
            if (geometry == null) continue;
            JsonObject location = geometry.getAsJsonObject("location");
            if (location == null || !location.has("lat") || !location.has("lng")) continue;
            points.add(new GeoPoint(
                    location.get("lat").getAsDouble(),
                    location.get("lng").getAsDouble()));
        }
        return points;
    }
}
