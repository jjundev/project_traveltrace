package com.traveltrace.app.domain;

import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.domain.model.TripSummary;

import java.util.List;

/**
 * 여행 저장/조회 (PRD §4.7). 모든 메서드는 백그라운드에서 실행되고
 * 결과를 <em>메인스레드</em>에서 콜백으로 돌려준다 — 호출부는 스레드를 신경 쓰지 않는다.
 */
public interface TripRepository {

    /** HOME 목록. 최신 생성순. */
    void list(Callback<List<TripSummary>> callback);

    /** 저장 여행 열기. 없으면 null 을 돌려준다. */
    void open(String tripId, Callback<TripDetail> callback);

    /** 여행과 연관 사진/위치를 지운다(cascade). 완료 시 null 로 콜백. */
    void delete(String tripId, Callback<Void> callback);
}
