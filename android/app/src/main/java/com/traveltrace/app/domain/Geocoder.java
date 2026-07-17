package com.traveltrace.app.domain;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;

import java.util.List;

/**
 * 이름/행정 지명 → 좌표 (PRD §4.4 분기). 결과가 다수(동명 지명)일 수 있어 List를 반환하며,
 * disambiguation 판단은 호출자(Epic E/F)가 한다. 실제 구현은 Epic E.
 */
public interface Geocoder {
    List<GeoPoint> geocode(GeocodeQuery query) throws Exception;
}
