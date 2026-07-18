package com.traveltrace.app.ui.map;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetUnknownPhotosBinding;

/** 위치 미상 드로어 (프로토타입 sheet:'unknown'). */
public class UnknownPhotosSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "unknown_sheet";
    private static final String ARG_COUNT = "count";
    private static final String ARG_TONES = "tones";
    private static final int COLUMNS = 4;

    private SheetUnknownPhotosBinding binding;

    // 톤은 호출자(host VM)가 넘긴다 — 시트는 ScreenFixtures 를 직접 부르지 않는다(seam 규칙).
    public static UnknownPhotosSheetFragment newInstance(int count, int[] tones) {
        UnknownPhotosSheetFragment f = new UnknownPhotosSheetFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_COUNT, count);
        args.putIntArray(ARG_TONES, tones);
        f.setArguments(args);
        return f;
    }

    /**
     * 콘텐츠 렌더만 분리 — 다이얼로그 없이 테스트할 수 있다.
     * 실제 썸네일은 로직 단계에서 붙고, 지금은 톤 색 타일로 대체한다.
     */
    public static void bindContent(SheetUnknownPhotosBinding binding, int count, int[] tones) {
        Context ctx = binding.getRoot().getContext();
        binding.unknownTitle.setText(ctx.getString(R.string.unknown_sheet_title, count));

        // 재바인딩 시 타일이 쌓이지 않도록 먼저 비운다.
        binding.unknownGrid.removeAllViews();

        int gap = ctx.getResources().getDimensionPixelSize(R.dimen.space_2);
        int radius = ctx.getResources().getDimensionPixelSize(R.dimen.radius_11);

        for (int i = 0; i < tones.length; i++) {
            View tile = new View(ctx);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(tones[i]);
            bg.setCornerRadius(radius);
            tile.setBackground(bg);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % COLUMNS, 1f);
            lp.rowSpec = GridLayout.spec(i / COLUMNS);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            tile.setLayoutParams(lp);

            binding.unknownGrid.addView(tile);
        }

        // GridLayout 은 폭만 4등분한다 → 레이아웃 확정 후 각 타일 높이를 폭에 맞춰 정사각으로.
        binding.unknownGrid.post(() -> {
            for (int i = 0; i < binding.unknownGrid.getChildCount(); i++) {
                View tile = binding.unknownGrid.getChildAt(i);
                if (tile.getWidth() > 0 && tile.getHeight() != tile.getWidth()) {
                    ViewGroup.LayoutParams lp = tile.getLayoutParams();
                    lp.height = tile.getWidth();
                    tile.setLayoutParams(lp);
                }
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetUnknownPhotosBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindContent(binding, requireArguments().getInt(ARG_COUNT),
                requireArguments().getIntArray(ARG_TONES));
        binding.unknownClose.setOnClickListener(v -> dismiss());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
