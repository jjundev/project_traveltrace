package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewOfflineBannerBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapOfflineBannerRendererTest {

    private ViewOfflineBannerBinding binding;

    @Before
    public void setUp() {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewOfflineBannerBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void onlineStateHidesTheBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map());

        assertEquals(View.GONE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void offlineStateShowsTheBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));

        assertEquals(View.VISIBLE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void theCopyPromisesTheRouteAndDeniesTheMapBackground() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));
        String text = binding.offlineBannerText.getText().toString();

        assertTrue("경로는 보인다고 말해야 한다: " + text, text.contains("경로"));
        assertTrue("지도(배경/타일)를 못 불러온다고 말해야 한다: " + text, text.contains("지도"));
    }

    @Test
    public void cinemaModeHidesTheBannerEvenWhenOffline() {
        MapUiState offlineCinema = new MapUiState("여행", 0, ScreenFixtures.map().stops, 0,
                false, false, true, MapUiState.Speed.NORMAL, true);

        MapRenderer.renderOfflineBanner(binding, offlineCinema);

        assertEquals("상영 모드는 크롬을 전부 숨긴다 — 배너도 예외가 아니다",
                View.GONE, binding.offlineBannerRoot.getVisibility());
    }

    @Test
    public void withOfflinePreservesEveryOtherFieldAndTheStopInstances() {
        MapUiState base = ScreenFixtures.map();

        MapUiState offline = base.withOffline(true);

        assertEquals(base.tripTitle, offline.tripTitle);
        assertEquals(base.unknownCount, offline.unknownCount);
        assertEquals(base.activeIndex, offline.activeIndex);
        assertEquals(base.speed, offline.speed);
        assertTrue("Stop 인스턴스를 새로 찍으면 MapReplayFragment.sameRoute() 가드가 깨져 "
                        + "지도가 다시 그려지고 카메라가 스냅된다",
                base.stops.get(0) == offline.stops.get(0));
    }
}
