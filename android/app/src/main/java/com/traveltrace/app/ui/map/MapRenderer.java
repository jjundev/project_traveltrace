package com.traveltrace.app.ui.map;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewCinemaOverlayBinding;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.databinding.ViewMapTopBarBinding;
import com.traveltrace.app.databinding.ViewOfflineBannerBinding;

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

    /**
     * 오프라인 한계 배너. 상영 모드에선 다른 크롬과 함께 숨는다 — 상영 중에 배너만 남으면
     * 연출이 깨지고, 어차피 상영을 나오면 다시 보인다.
     */
    public static void renderOfflineBanner(ViewOfflineBannerBinding binding, MapUiState state) {
        binding.offlineBannerRoot.setVisibility(
                state.offline && !state.cinema ? View.VISIBLE : View.GONE);
    }

    /** 선택 탭 = 파란 pill + 흰 글자 / 비선택 = 투명 + tertiary 글자 (프로토타입 mapTabBg/Fg). */
    private static void applyTab(Context ctx, TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_segment_selected : 0);
        tab.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.text_on_fill : R.color.text_tertiary));
    }

    public static void renderSheet(ViewMapBottomSheetBinding binding, MapUiState state) {
        Context ctx = binding.getRoot().getContext();

        if (state.stops.isEmpty()) {
            // 사진이 전부 위치 미상이면 정차 지점이 없다 — activeStop() 은 여기서 던진다.
            binding.photoBanner.setVisibility(View.GONE);
            binding.scrubber.setVisibility(View.GONE);
            binding.controlsRow.setVisibility(View.GONE);
            return;
        }
        binding.photoBanner.setVisibility(View.VISIBLE);
        binding.scrubber.setVisibility(View.VISIBLE);
        binding.controlsRow.setVisibility(View.VISIBLE);

        MapUiState.Stop stop = state.activeStop();

        binding.photoTone.setBackgroundColor(stop.toneColor);
        binding.stopName.setText(stop.name);
        binding.stopMeta.setText(ctx.getString(R.string.map_stop_meta,
                stop.time, state.activeIndex + 1, state.stops.size()));

        binding.badgeGps.setVisibility(stop.ai ? View.GONE : View.VISIBLE);
        binding.badgeApprox.setVisibility(stop.ai ? View.VISIBLE : View.GONE);
        // "빼기"는 AI 근사 위치에만 뜬다 — GPS 좌표는 뺄 이유가 없다.
        binding.detachButton.setVisibility(stop.ai ? View.VISIBLE : View.GONE);

        if (stop.extra > 0) {
            binding.extraBadge.setVisibility(View.VISIBLE);
            binding.extraBadge.setText(ctx.getString(R.string.map_extra_photos, stop.extra));
        } else {
            binding.extraBadge.setVisibility(View.GONE);
        }

        binding.scrubber.setStops(state.stops);
        binding.scrubber.setActiveIndex(state.activeIndex);

        binding.playButton.setImageResource(state.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        binding.playButton.setContentDescription(ctx.getString(
                state.playing ? R.string.map_pause_desc : R.string.map_play_desc));

        // 양 끝에서는 이전/다음을 흐리게 (프로토타입 prevColor/nextColor).
        binding.prevButton.setImageTintList(tint(ctx, state.activeIndex > 0));
        binding.nextButton.setImageTintList(
                tint(ctx, state.activeIndex < state.stops.size() - 1));

        applySpeed(ctx, binding.speedRelaxed, state.speed == MapUiState.Speed.RELAXED);
        applySpeed(ctx, binding.speedNormal, state.speed == MapUiState.Speed.NORMAL);
        applySpeed(ctx, binding.speedFast, state.speed == MapUiState.Speed.FAST);
    }

    public static void renderCinema(ViewCinemaOverlayBinding binding, MapUiState state) {
        binding.cinemaRoot.setVisibility(state.cinema ? View.VISIBLE : View.GONE);
        if (!state.cinema) return;

        Context ctx = binding.getRoot().getContext();
        MapUiState.Stop stop = state.activeStop();

        // 톤 배경 + 20dp 라운드: 톤 색이 상태마다 달라 드로어블 리소스로 고정할 수 없다.
        GradientDrawable tone = new GradientDrawable();
        tone.setColor(stop.toneColor);
        tone.setCornerRadius(ctx.getResources().getDimension(R.dimen.radius_20));
        binding.cinemaTone.setBackground(tone);

        int cornerRadiusPx = ctx.getResources().getDimensionPixelSize(R.dimen.radius_20);
        binding.cinemaCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
            }
        });
        binding.cinemaCard.setClipToOutline(true);

        binding.cinemaName.setText(stop.name);
        // 도시명은 역지오코딩이 필요해 S1 범위 밖이다 — 여행 이름을 쓴다.
        binding.cinemaMeta.setText(ctx.getString(R.string.cinema_meta, stop.time, state.tripTitle));
    }

    private static ColorStateList tint(Context ctx, boolean enabled) {
        return ColorStateList.valueOf(ContextCompat.getColor(ctx,
                enabled ? R.color.text_primary : R.color.border_strong));
    }

    /** 선택 속도 = 흰 pill + primary 글자 / 비선택 = 투명 + tertiary 글자. */
    private static void applySpeed(Context ctx, TextView pill, boolean selected) {
        pill.setBackgroundResource(selected ? R.drawable.bg_speed_selected : 0);
        pill.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.text_primary : R.color.text_tertiary));
    }
}
