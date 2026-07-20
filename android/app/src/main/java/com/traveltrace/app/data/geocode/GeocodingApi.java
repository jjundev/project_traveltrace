package com.traveltrace.app.data.geocode;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/** Geocoding API. base URL 은 {@code https://maps.googleapis.com/}. */
public interface GeocodingApi {

    @GET("maps/api/geocode/json")
    Call<JsonObject> geocode(@Query("address") String address, @Query("key") String apiKey);
}
