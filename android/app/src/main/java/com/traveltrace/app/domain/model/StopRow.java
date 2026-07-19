package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationSource;

/** 경로 위 정차 지점 1개. PLACED 인 사진만 이 형태로 나온다. */
public class StopRow {
    public String photoId;
    public long mediaStoreId;

    /** PLACED 는 항상 non-null 이지만 Room 컬럼이 nullable 이라 박싱 타입으로 받는다. */
    @Nullable
    public Long takenAtUtc;

    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    public LocationSource source;

    @Nullable
    public String landmarkName;

    public String displayName;
}
