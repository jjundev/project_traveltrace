package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** plan/04-data-layer.md 의 PhotoLocation 스키마. photoId 가 PK 이자 FK. */
@Entity(
        tableName = "photo_locations",
        foreignKeys = @ForeignKey(
                entity = PhotoEntity.class,
                parentColumns = "id",
                childColumns = "photoId",
                onDelete = ForeignKey.CASCADE))
public class PhotoLocationEntity {

    @PrimaryKey
    @NonNull
    public String photoId = "";

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

    /** 리플레이에서 위치 미상으로 뺀 상태 (S6). S1 은 항상 false. */
    public boolean detached;
}
