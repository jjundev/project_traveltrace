package com.traveltrace.app.data;

import com.traveltrace.app.core.model.Trip;
import com.traveltrace.app.domain.TripRepository;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** 빈 스텁 (Epic I에서 Room 구현). 안전한 no-op / 빈 반환. */
public class TripRepositoryStub implements TripRepository {

    @Inject
    public TripRepositoryStub() {
    }

    @Override
    public void save(Trip trip) {
        // no-op (Epic I)
    }

    @Override
    public Trip load(String id) {
        return null;
    }

    @Override
    public List<Trip> list() {
        return Collections.emptyList();
    }
}
