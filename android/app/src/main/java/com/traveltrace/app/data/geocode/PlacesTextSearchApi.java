package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

/**
 * Places API (New) Text Search. base URL 은 {@code https://places.googleapis.com/}.
 *
 * <p>{@code X-Goog-FieldMask} 는 선택이 아니라 <b>필수</b>다 — 빠지면 400 이다. 또한
 * 요청한 필드가 과금 티어를 결정하므로 좌표만 받는다(Places SDK 대신 REST 를 쓰는 이유
 * 중 하나이며, 블로킹 {@code Geocoder} 계약에도 REST 가 자연스럽게 맞는다).
 */
public interface PlacesTextSearchApi {

    @Headers("Content-Type: application/json")
    @POST("v1/places:searchText")
    Call<JsonObject> searchText(@Header("X-Goog-Api-Key") String apiKey,
                                @Header("X-Goog-FieldMask") String fieldMask,
                                @Body JsonObject body);
}
