package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import com.traveltrace.app.BuildConfig;
import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.domain.Geocoder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Response;

/**
 * {@link Geocoder} 실구현 (PRD §4.4). 어느 API 로 갈지는 {@link GeocodeQuery} 의
 * <em>타입</em>이 이미 결정해 두었다 — 분기 로직이 여기에 다시 있으면 두 벌이 어긋난다.
 *
 * <p>블로킹이다(인터페이스 계약). 호출자는 {@code AppExecutors.io()} 위에 있다.
 *
 * <p>zero-result 는 빈 리스트로 <em>돌려주고</em>, HTTP 실패는 <em>던진다</em>.
 * 전자는 "이 이름은 좌표화 불가"라는 최종 답이라 재시도가 의미 없고, 후자는 S4 의
 * 재시도가 붙어야 할 일시적 실패다.
 */
@Singleton
public class RoutingGeocoder implements Geocoder {

    /** 좌표만 받는다 — 필드가 늘면 Places 과금 티어가 올라간다. */
    private static final String PLACES_FIELD_MASK = "places.location";

    /** 동명 지명 판정에 쓸 만큼만. 1이면 Disambiguator 가 볼 게 없다. */
    private static final int MAX_PLACES_RESULTS = 5;

    private final PlacesTextSearchApi places;
    private final GeocodingApi geocoding;

    @Inject
    public RoutingGeocoder(PlacesTextSearchApi places, GeocodingApi geocoding) {
        this.places = places;
        this.geocoding = geocoding;
    }

    @Override
    public List<GeoPoint> geocode(GeocodeQuery query) throws Exception {
        if (query instanceof GeocodeQuery.Poi) {
            return searchPlaces(((GeocodeQuery.Poi) query).name);
        }
        if (query instanceof GeocodeQuery.AdministrativePlace) {
            return searchGeocoding(((GeocodeQuery.AdministrativePlace) query).text);
        }
        return Collections.emptyList();
    }

    private List<GeoPoint> searchPlaces(String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("textQuery", name);
        body.addProperty("maxResultCount", MAX_PLACES_RESULTS);

        Response<JsonObject> response = places.searchText(
                BuildConfig.PLACES_API_KEY, PLACES_FIELD_MASK, body).execute();
        return unwrap(response, "Places searchText");
    }

    private List<GeoPoint> searchGeocoding(String address) throws IOException {
        Response<JsonObject> response =
                geocoding.geocode(address, BuildConfig.GEOCODING_API_KEY).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Geocoding HTTP " + response.code());
        }
        JsonObject payload = response.body();
        // status 확인은 파서가 한다 — Geocoding 은 200 에 실패를 실어 보낸다.
        return payload == null
                ? Collections.<GeoPoint>emptyList()
                : GeocodingResponseParser.parse(payload);
    }

    private static List<GeoPoint> unwrap(Response<JsonObject> response, String what)
            throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException(what + " HTTP " + response.code());
        }
        JsonObject payload = response.body();
        return payload == null
                ? Collections.<GeoPoint>emptyList()
                : PlacesResponseParser.parse(payload);
    }
}
