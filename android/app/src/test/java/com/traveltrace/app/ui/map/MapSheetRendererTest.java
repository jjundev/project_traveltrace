package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapSheetRendererTest {

    private Context ctx;
    private ViewMapBottomSheetBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewMapBottomSheetBinding.inflate(LayoutInflater.from(ctx));
    }

    /** index 를 바꾼 상태 사본. */
    private static MapUiState at(int index) {
        MapUiState b = ScreenFixtures.map();
        return new MapUiState(b.tripTitle, b.unknownCount, b.stops, index,
                b.playing, b.satellite, b.cinema, b.speed);
    }

    @Test
    public void gpsStop_showsGpsBadgeAndHidesApproxAndDetach() {
        MapRenderer.renderSheet(binding, at(0));

        assertEquals("개선문", binding.stopName.getText().toString());
        assertEquals("10:12 · 1/6번째", binding.stopMeta.getText().toString());
        assertEquals(View.VISIBLE, binding.badgeGps.getVisibility());
        assertEquals(View.GONE, binding.badgeApprox.getVisibility());
        assertEquals(View.GONE, binding.detachButton.getVisibility());
    }

    @Test
    public void aiStop_showsApproxBadgeAndDetachButton() {
        MapRenderer.renderSheet(binding, at(3)); // 루브르 = AI

        assertEquals("루브르 박물관", binding.stopName.getText().toString());
        assertEquals(View.GONE, binding.badgeGps.getVisibility());
        assertEquals(View.VISIBLE, binding.badgeApprox.getVisibility());
        assertEquals(View.VISIBLE, binding.detachButton.getVisibility());
    }

    @Test
    public void extraBadgeShowsOnlyWhenStopHasExtraPhotos() {
        MapRenderer.renderSheet(binding, at(0)); // 개선문 extra=0
        assertEquals(View.GONE, binding.extraBadge.getVisibility());

        MapRenderer.renderSheet(binding, at(1)); // 에펠탑 extra=4
        assertEquals(View.VISIBLE, binding.extraBadge.getVisibility());
        assertEquals("+4장", binding.extraBadge.getText().toString());
    }

    @Test
    public void scrubberTracksActiveIndex() {
        MapRenderer.renderSheet(binding, at(2));
        assertEquals(2, binding.scrubber.getActiveIndex());
    }

    @Test
    public void playIconTogglesWithPlayingState() {
        MapUiState paused = ScreenFixtures.map();
        MapRenderer.renderSheet(binding, paused);
        assertEquals(ctx.getString(R.string.map_play_desc),
                binding.playButton.getContentDescription().toString());

        MapUiState playing = new MapUiState(paused.tripTitle, paused.unknownCount, paused.stops,
                paused.activeIndex, true, paused.satellite, paused.cinema, paused.speed);
        MapRenderer.renderSheet(binding, playing);
        assertEquals(ctx.getString(R.string.map_pause_desc),
                binding.playButton.getContentDescription().toString());
    }

    @Test
    public void normalSpeedIsHighlightedByDefault() {
        MapRenderer.renderSheet(binding, ScreenFixtures.map());

        assertEquals(ctx.getColor(R.color.text_primary), binding.speedNormal.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary),
                binding.speedRelaxed.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary), binding.speedFast.getCurrentTextColor());
    }

    @Test
    public void noStops_hidesBannerScrubberAndControlsWithoutThrowing() {
        // 사진이 전부 위치 미상이면 정차 지점이 없다 — activeStop() 은 이 경우 던진다.
        // 픽스처는 항상 6개 정류장을 갖고 있어 이 상태는 여기서 직접 만든다
        // (ScreenFixtures 에 빈 픽스처를 추가하지 않는다).
        MapUiState empty = new MapUiState("여행", 0, Collections.emptyList(), 0,
                false, false, false, MapUiState.Speed.NORMAL);

        MapRenderer.renderSheet(binding, empty);

        assertEquals(View.GONE, binding.photoBanner.getVisibility());
        assertEquals(View.GONE, binding.scrubber.getVisibility());
        assertEquals(View.GONE, binding.controlsRow.getVisibility());
    }
}
