package com.traveltrace.app.domain;

import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.List;

/** 분석 결과를 여행 1건으로 확정 저장한다. */
public interface PhotoAnalysisRepository {

    /**
     * 결과 전체를 한 트랜잭션으로 저장하고 새 tripId 를 돌려준다.
     * 기간·장수·일수는 results 에서 파생되므로 호출부가 계산하지 않는다.
     */
    void saveTrip(String name, String timeZoneId, List<PhotoAnalysis> results,
                  Callback<String> callback);
}
