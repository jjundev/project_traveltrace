package com.traveltrace.app.ui.photo;

import android.net.Uri;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SELECT 화면이 렌더할 불변 상태. */
public final class PhotoSelectionUiState {

    public final String periodLabel;
    public final int maxCount;
    public final List<Tile> tiles;

    public PhotoSelectionUiState(String periodLabel, int maxCount, List<Tile> tiles) {
        this.periodLabel = periodLabel;
        this.maxCount = maxCount;
        this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
    }

    public int selectedCount() {
        int n = 0;
        for (Tile t : tiles) {
            if (t.selected) n++;
        }
        return n;
    }

    /** 분석에 넘길 사진 식별자. 화면 순서를 그대로 유지한다. */
    public List<Long> selectedMediaStoreIds() {
        List<Long> ids = new ArrayList<>();
        for (Tile t : tiles) {
            if (t.selected) ids.add(t.mediaStoreId);
        }
        return ids;
    }

    /**
     * index 타일의 선택 상태만 뒤집은 새 상태를 만든다 (원본 불변).
     *
     * <p>사진 식별자(mediaStoreId·contentUri)를 반드시 함께 실어 나른다 — 빠뜨리면
     * 탭할 때마다 썸네일이 색블록으로 되돌아가고 저장이 엉뚱한 사진을 가리킨다.
     */
    public PhotoSelectionUiState withToggled(int index) {
        List<Tile> next = new ArrayList<>(tiles);
        Tile t = next.get(index);
        next.set(index, new Tile(
                t.toneColor, t.label, !t.selected, t.mediaStoreId, t.contentUri));
        return new PhotoSelectionUiState(periodLabel, maxCount, next);
    }

    /** 그리드 타일 1개. contentUri 가 null 이면 톤 색으로 그린다(픽스처·프리뷰 경로). */
    public static final class Tile {
        @ColorInt public final int toneColor;
        /** 라벨 pill 문구. 없으면 null. */
        @Nullable public final String label;
        public final boolean selected;
        public final long mediaStoreId;
        /** 실제 사진. 픽스처에서는 null. */
        @Nullable public final Uri contentUri;

        public Tile(@ColorInt int toneColor, @Nullable String label, boolean selected,
                    long mediaStoreId, @Nullable Uri contentUri) {
            this.toneColor = toneColor;
            this.label = label;
            this.selected = selected;
            this.mediaStoreId = mediaStoreId;
            this.contentUri = contentUri;
        }
    }
}
