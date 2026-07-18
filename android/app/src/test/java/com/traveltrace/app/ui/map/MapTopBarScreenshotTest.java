package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.ViewMapTopBarBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * MAP 상단바를 PNG 로 캡처한다(프로토타입 육안 대조 + 골든 회귀).
 *
 * <p>MAP 화면 전체(fragment_map_replay)는 SupportMapFragment 를 담은 FragmentContainerView 때문에
 * Robolectric 이 단독 인플레이트할 수 없다. 그래서 크롬만 담은 include 레이아웃을 캡처한다 —
 * 지도 타일 자체는 스크린샷 대상이 아니다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class MapTopBarScreenshotTest {

    private ScreenshotHarness harness;
    private ViewMapTopBarBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = ViewMapTopBarBinding.inflate(harness.inflater());
    }

    /** 지도 탭 선택 상태 (기본). */
    @Test
    public void topBar_mapTab() {
        MapRenderer.renderTopBar(binding, ScreenFixtures.map());
        harness.captureWrapContentHeight(binding.getRoot(), "map_topbar_map.png");
    }

    /** 위성 탭 선택 상태 — 세그먼트 하이라이트가 넘어가는지. */
    @Test
    public void topBar_satelliteTab() {
        MapUiState base = ScreenFixtures.map();
        MapUiState satellite = new MapUiState(base.tripTitle, base.unknownCount, base.stops,
                base.activeIndex, base.playing, true, base.cinema, base.speed);

        MapRenderer.renderTopBar(binding, satellite);
        harness.captureWrapContentHeight(binding.getRoot(), "map_topbar_satellite.png");
    }
}
