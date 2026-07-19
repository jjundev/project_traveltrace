package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

/** HOME 카드 1장을 채우는 읽기 모델. Room @Query 가 직접 채운다. */
public class TripSummary {
    public String id;
    public String name;
    public int photoCount;
    public int dayCount;
    public long startDateUtc;
    public String timeZoneId;

    /** 카드 hero 로 쓸 첫 사진. 사진이 없으면 null. */
    @Nullable
    public Long heroMediaStoreId;
}
