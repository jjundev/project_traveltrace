package com.traveltrace.app.domain;

import com.traveltrace.app.core.model.Trip;

import java.util.List;

/** 여행 저장/조회 (PRD §4.7). 구현은 Epic I에서 Room으로. */
public interface TripRepository {
    void save(Trip trip);

    Trip load(String id);

    List<Trip> list();
}
