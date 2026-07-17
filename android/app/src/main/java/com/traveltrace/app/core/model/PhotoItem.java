package com.traveltrace.app.core.model;

/**
 * 선택된 사진의 안정적 핸들 (PRD §4.7 — 캐시/저장 키 = MediaStore _ID + 콘텐츠 해시).
 * contentHash 는 분석 스트림 중 1회 계산(Epic C/H/I), 미계산 시 null.
 */
public final class PhotoItem {
    public final long mediaStoreId;
    public final String uri;
    public final String contentHash;

    public PhotoItem(long mediaStoreId, String uri, String contentHash) {
        this.mediaStoreId = mediaStoreId;
        this.uri = uri;
        this.contentHash = contentHash;
    }
}
