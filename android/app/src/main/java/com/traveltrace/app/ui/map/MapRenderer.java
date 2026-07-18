package com.traveltrace.app.ui.map;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapTopBarBinding;

/**
 * MapUiState → MAP 크롬 반영. 지도 레이아웃(FragmentContainerView)과 분리된 include
 * 레이아웃만 다루므로 Robolectric 이 단독 검증할 수 있다.
 * Task 9·10 이 renderSheet / renderCinema 를 이 클래스에 추가한다.
 */
public final class MapRenderer {

    private MapRenderer() {}

    public static void renderTopBar(ViewMapTopBarBinding binding, MapUiState state) {
        Context ctx = binding.getRoot().getContext();

        binding.mapTitle.setText(state.tripTitle);
        binding.unknownChipText.setText(
                ctx.getString(R.string.map_unknown_chip, state.unknownCount));
        binding.unknownChip.setVisibility(state.unknownCount > 0 ? View.VISIBLE : View.GONE);

        applyTab(ctx, binding.tabMap, !state.satellite);
        applyTab(ctx, binding.tabSatellite, state.satellite);
    }

    /** 선택 탭 = 파란 pill + 흰 글자 / 비선택 = 투명 + tertiary 글자 (프로토타입 mapTabBg/Fg). */
    private static void applyTab(Context ctx, TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_segment_selected : 0);
        tab.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.text_on_fill : R.color.text_tertiary));
    }
}
