package com.traveltrace.app.data;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.domain.Geocoder;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** 빈 스텁 (Epic E에서 Places/Geocoding 구현). 안전하게 빈 결과 반환. */
public class GeocoderStub implements Geocoder {

    @Inject
    public GeocoderStub() {
    }

    @Override
    public List<GeoPoint> geocode(GeocodeQuery query) {
        return Collections.emptyList();
    }
}
