package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.ViewOfflineBannerBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 오프라인 배너를 PNG 로 캡처한다. 상단바와 같은 이유로 include 레이아웃만 단독 캡처한다 —
 * MAP 화면 전체는 SupportMapFragment 때문에 Robolectric 이 인플레이트할 수 없다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class MapOfflineBannerScreenshotTest {

    private ScreenshotHarness harness;
    private ViewOfflineBannerBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = ViewOfflineBannerBinding.inflate(harness.inflater());
    }

    @Test
    public void offlineBanner() {
        MapRenderer.renderOfflineBanner(binding, ScreenFixtures.map().withOffline(true));
        harness.captureWrapContentHeight(binding.getRoot(), "map_offline_banner.png");
    }
}
