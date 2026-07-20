package com.traveltrace.app.data;

import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.domain.Geocoder;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** 빈 스텁 (Epic E 에서 Places/Geocoding 구현). 안전하게 빈 결과 반환. */
public class GeocoderStub implements Geocoder {

    private final AnalysisCostLog costLog;

    @Inject
    public GeocoderStub(AnalysisCostLog costLog) {
        this.costLog = costLog;
    }

    @Override
    public List<GeoPoint> geocode(GeocodeQuery query) {
        // 스텁이라 실제 네트워크는 안 나가지만 경계는 여기다 — 실 구현으로 바뀌어도
        // 카운터 지점이 그대로 남는다.
        costLog.recordGeocodeCall();
        return Collections.emptyList();
    }
}
