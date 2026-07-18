package com.traveltrace.app.ui.photo;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** 3열 그리드의 6dp 균등 간격 (프로토타입 grid gap:6px). */
public class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spanCount;
    private final int spacing;

    public GridSpacingDecoration(int spanCount, int spacing) {
        this.spanCount = spanCount;
        this.spacing = spacing;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;
        int column = position % spanCount;

        outRect.left = spacing * column / spanCount;
        outRect.right = spacing - (spacing * (column + 1) / spanCount);
        if (position >= spanCount) {
            outRect.top = spacing;
        }
    }
}
