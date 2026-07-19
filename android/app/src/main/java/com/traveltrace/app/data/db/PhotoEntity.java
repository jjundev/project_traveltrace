package com.traveltrace.app.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * plan/04-data-layer.md 의 Photo 스키마.
 *
 * <p>contentHash 는 S1 에서 채우지 않는다 — 해시는 S3 업로드 파이프라인이 사진 스트림을
 * 읽는 김에 1회 계산한다(중복 I/O 회피, plan/04). 여기선 컬럼만 미리 만든다.
 */
@Entity(
        tableName = "photos",
        foreignKeys = @ForeignKey(
                entity = TripEntity.class,
                parentColumns = "id",
                childColumns = "tripId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("tripId")})
public class PhotoEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    /**
     * NULL 이면 SQLite 가 ON DELETE CASCADE 를 적용하지 않아(FK 는 NULL 자식엔 강제되지
     * 않는다) 사진 행이 orphan 으로 남는다 — 항상 있어야 한다(finding 4).
     */
    @NonNull
    public String tripId = "";

    /** 기기 내에서 안정적인 캐시 키의 절반 (PRD §4.1 MediaStore 채택 사유). */
    public long mediaStoreId;

    @Nullable
    public String contentHash;

    /** UTC millis. 촬영 시각을 못 읽었으면 null → classification=NO_TIME. */
    @Nullable
    public Long takenAtUtc;

    /** EXIF 에 OffsetTimeOriginal 이 있었는지. false 면 기기 타임존으로 폴백한 값이다. */
    public boolean takenAtHasOffset;

    public String displayName;
}
