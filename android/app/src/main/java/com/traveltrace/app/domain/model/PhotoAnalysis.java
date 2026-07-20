package com.traveltrace.app.domain.model;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** 사진 1장의 분석 결과(EXIF + AI). 저장 전 단계의 운반 객체. */
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

    /** AI 가 인식한 랜드마크/POI 이름. GPS 사진과 인식 실패는 null. */
    @Nullable
    public String landmarkName;

    @Nullable
    public String city;

    @Nullable
    public String country;

    /** AI 인식 신뢰도(0~1). AI 를 타지 않은 사진은 null — 0 이 아니다. */
    @Nullable
    public Double confidence;

    /**
     * 업로드 파이프라인이 스트림을 읽는 김에 계산한 SHA-256 (PRD §4.7).
     *
     * <p>AI 경로를 탄 사진에만 채워진다. GPS 사진은 업로드 자체를 하지 않으므로
     * 다운스케일·해시 비용을 낼 이유가 없다 — 캐시(S4)가 GPS 사진까지 필요로 하면
     * 그때 해시 전용 경로를 따로 만든다.
     */
    @Nullable
    public String contentHash;
}
