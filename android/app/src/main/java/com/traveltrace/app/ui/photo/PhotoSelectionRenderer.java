package com.traveltrace.app.ui.photo;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

/** PhotoSelectionUiState → SELECT 뷰 반영. */
public final class PhotoSelectionRenderer {

    private static final int SPAN_COUNT = 3;

    private PhotoSelectionRenderer() {}

    public static void render(FragmentPhotoSelectionBinding binding,
                              PhotoSelectionUiState state,
                              PhotoGridAdapter.Listener listener) {
        Context ctx = binding.getRoot().getContext();

        binding.periodLabel.setText(state.periodLabel);
        binding.selectHint.setText(ctx.getString(R.string.select_hint, state.maxCount));
        binding.selectCount.setText(buildCountText(ctx, state.selectedCount()));

        PhotoGridAdapter adapter;
        if (binding.photoGrid.getAdapter() instanceof PhotoGridAdapter) {
            adapter = (PhotoGridAdapter) binding.photoGrid.getAdapter();
        } else {
            adapter = new PhotoGridAdapter(listener);
            binding.photoGrid.setLayoutManager(new GridLayoutManager(ctx, SPAN_COUNT));
            binding.photoGrid.addItemDecoration(
                    new GridSpacingDecoration(SPAN_COUNT,
                            ctx.getResources().getDimensionPixelSize(R.dimen.photo_grid_gap)));
            binding.photoGrid.setAdapter(adapter);
        }
        adapter.submit(state.tiles);
    }

    /** "선택한 사진 <N>장" — 숫자만 브랜드 색 (프로토타입 span). */
    private static CharSequence buildCountText(Context ctx, int count) {
        String prefix = ctx.getString(R.string.select_count_prefix);
        String number = String.valueOf(count);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(prefix).append(number).append(ctx.getString(R.string.select_count_suffix));
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_brand)),
                prefix.length(), prefix.length() + number.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
