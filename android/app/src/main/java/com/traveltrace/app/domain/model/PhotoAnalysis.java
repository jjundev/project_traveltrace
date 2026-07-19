package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** 사진 1장의 EXIF 추출 결과. 저장 전 단계의 운반 객체. */
public class PhotoAnalysis {
    public long mediaStoreId;
    public String displayName;

    /** 촬영 시각(UTC millis). 못 읽었으면 null. */
    @Nullable
    public Long takenAtUtc;

    public boolean takenAtHasOffset;

    /** 좌표가 없으면 null 로 둔다 — 0.0 으로 채우면 (0,0) 핀이 생긴다. */
    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    public LocationSource source;
    public LocationClassification classification;
}
