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
        JsonElement statusElement = response.get("status");
        if (!statusElement.isJsonPrimitive() || !STATUS_OK.equals(statusElement.getAsString())) {
            return points;   // null·ZERO_RESULTS·REQUEST_DENIED·OVER_QUERY_LIMIT 등.
        }
        JsonElement resultsElement = response.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return points;
        }
        JsonArray results = resultsElement.getAsJsonArray();
        for (int i = 0; i < results.size(); i++) {
            JsonElement entry = results.get(i);
            if (!entry.isJsonObject()) continue;
            JsonElement geometryElement = entry.getAsJsonObject().get("geometry");
            if (geometryElement == null || !geometryElement.isJsonObject()) continue;
            JsonElement locationElement = geometryElement.getAsJsonObject().get("location");
            if (locationElement == null || !locationElement.isJsonObject()) continue;
            JsonObject location = locationElement.getAsJsonObject();
            JsonElement lat = location.get("lat");
            JsonElement lng = location.get("lng");
            if (lat == null || lng == null || !lat.isJsonPrimitive() || !lng.isJsonPrimitive()) {
                continue;
            }
            // getAsDouble() 은 String/Boolean 을 감싼 JsonPrimitive 에도 반응한다 —
            // 숫자가 아닌 문자열이면 Double.parseDouble 이 NumberFormatException 을 던진다.
            // 이 항목만 건너뛰고 나머지 파싱은 계속한다.
            try {
                points.add(new GeoPoint(lat.getAsDouble(), lng.getAsDouble()));
            } catch (RuntimeException skip) {
                continue;
            }
        }
        return points;
    }
}
