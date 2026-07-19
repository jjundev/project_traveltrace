package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/** plan/04-data-layer.md 의 Trip 스키마. costSpent·engineMode 는 S4/S5 가 채운다. */
@Entity(tableName = "trips")
public class TripEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String name;

    @Nullable
    public String coverEmoji;

    @Nullable
    public String region;

    public int photoCount;

    public int dayCount;

    public long startDateUtc;

    public long endDateUtc;

    /** 여행 기준 타임존 (PRD §4.2, v1 은 여행당 1개). */
    public String timeZoneId;

    public long createdAt;

    /** S4 비용 상한 집계용. S1 은 0 으로 둔다. */
    public double costSpent;

    /** S5 엔진 모드(dual/single). S1 은 null. */
    @Nullable
    public String engineMode;
}
