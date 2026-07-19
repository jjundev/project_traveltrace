package com.traveltrace.app.data.media;

import android.net.Uri;

import androidx.annotation.Nullable;

/** MediaStore 행 1개. 썸네일 비트맵은 들고 있지 않다 — Glide 가 바인드 시점에 읽는다. */
public class GalleryImage {

    public final long id;
    public final Uri contentUri;
    public final String displayName;

    /** MediaStore 가 아는 촬영 시각(UTC millis). 모르면 null. EXIF 가 최종 판정한다. */
    @Nullable
    public final Long dateTakenUtc;

    public GalleryImage(long id, Uri contentUri, String displayName,
                        @Nullable Long dateTakenUtc) {
        this.id = id;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.dateTakenUtc = dateTakenUtc;
    }
}
