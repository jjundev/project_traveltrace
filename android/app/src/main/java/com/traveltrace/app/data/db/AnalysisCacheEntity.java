package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/**
 * plan/04-data-layer.md 의 AnalysisCache. 키 = (MediaStore _ID + 콘텐츠 해시).
 *
 * <p><b>여행과 독립이다</b> — trips 로의 FK 가 일부러 없다. 여행을 지워도 이 행은 남아야
 * 다음 여행이 같은 사진을 다시 분석하지 않는다(PRD §4.7 "다른 여행에서도 hit").
 *
 * <p>휘발성 {@code content://} URI 를 키로 쓰지 않는 이유는 plan/04 참고 — 재부팅·재설치
 * 후에도 안정적인 _ID 와, 사진이 편집되면 바뀌는 콘텐츠 해시의 조합이라야 "같은 사진"을
 * 정확히 뜻한다.
 */
@Entity(tableName = "analysis_cache", primaryKeys = {"mediaStoreId", "contentHash"})
public class AnalysisCacheEntity {

    public long mediaStoreId;

    @NonNull
    public String contentHash = "";

    /** 좌표가 없으면 null. 절대 0.0 으로 채우지 않는다 — (0,0) 핀 방지. */
    @Nullable
    public Double lat;

    @Nullable
    public Double lng;

    @NonNull
    public LocationSource source = LocationSource.NONE;

    @Nullable
    public String landmarkName;

    @Nullable
    public String city;

    @Nullable
    public String country;

    @NonNull
    public LocationClassification classification = LocationClassification.UNKNOWN;

    @Nullable
    public Double confidence;

    /** 촬영 시각(UTC millis). 같은 바이트에서 파생되므로 캐시가 함께 들고 있는다. */
    @Nullable
    public Long takenAtUtc;

    public boolean takenAtHasOffset;

    /** 결과를 만든 vision 모델 ID. S1/S8 의 GPS 결과는 모델이 없어 null. S5 가 채운다. */
    @Nullable
    public String model;

    public long createdAt;
}
