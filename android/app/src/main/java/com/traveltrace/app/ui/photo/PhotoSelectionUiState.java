package com.traveltrace.app.ui.photo;

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
        this.tiles = Collections.unmodifiableList(tiles);
    }

    public int selectedCount() {
        int n = 0;
        for (Tile t : tiles) {
            if (t.selected) n++;
        }
        return n;
    }

    /** index 타일의 선택 상태만 뒤집은 새 상태를 만든다 (원본 불변). */
    public PhotoSelectionUiState withToggled(int index) {
        List<Tile> next = new ArrayList<>(tiles);
        Tile t = next.get(index);
        next.set(index, new Tile(t.toneColor, t.label, !t.selected));
        return new PhotoSelectionUiState(periodLabel, maxCount, next);
    }

    /** 그리드 타일 1개. 실제 사진은 로직 단계에서 붙고, 지금은 톤 색으로 대체한다. */
    public static final class Tile {
        @ColorInt public final int toneColor;
        /** 라벨 pill 문구. 없으면 null. */
        @Nullable public final String label;
        public final boolean selected;

        public Tile(@ColorInt int toneColor, @Nullable String label, boolean selected) {
            this.toneColor = toneColor;
            this.label = label;
            this.selected = selected;
        }
    }
}
