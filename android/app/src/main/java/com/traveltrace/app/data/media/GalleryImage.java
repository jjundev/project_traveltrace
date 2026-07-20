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

    /**
     * 파일 크기(바이트). 캐시 키의 콘텐츠 해시에 섞인다 — 커서에서 이미 읽어 오므로
     * 파일을 한 번 더 열지 않아도 된다(plan/04 "앞부분+크기" 결정). 모르면 0.
     */
    public final long sizeBytes;

    public GalleryImage(long id, Uri contentUri, String displayName,
                        @Nullable Long dateTakenUtc, long sizeBytes) {
        this.id = id;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.dateTakenUtc = dateTakenUtc;
        this.sizeBytes = sizeBytes;
    }
}
