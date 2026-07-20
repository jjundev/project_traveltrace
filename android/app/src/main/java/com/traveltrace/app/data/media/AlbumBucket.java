package com.traveltrace.app.data.media;

/** 갤러리 폴더(앨범) 1개 — {@code BUCKET_ID}/{@code BUCKET_DISPLAY_NAME} 로 집계한 결과. */
public class AlbumBucket {
    public final String bucketId;
    public final String displayName;
    public final int count;

    public AlbumBucket(String bucketId, String displayName, int count) {
        this.bucketId = bucketId;
        this.displayName = displayName;
        this.count = count;
    }
}
