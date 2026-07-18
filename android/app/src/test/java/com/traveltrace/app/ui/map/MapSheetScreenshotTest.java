package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * MAP 하단 시트를 PNG 로 캡처한다(프로토타입 육안 대조 + 골든 회귀).
 *
 * <p>MAP 화면 전체는 SupportMapFragment 를 담은 FragmentContainerView 때문에 단독 인플레이트가
 * 불가능하므로, 시트 include 레이아웃만 캡처한다. 시트는 wrap_content 높이다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class MapSheetScreenshotTest {

    private ScreenshotHarness harness;
    private ViewMapBottomSheetBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = ViewMapBottomSheetBinding.inflate(harness.inflater());
    }

    /** activeIndex 를 바꾼 상태 사본. */
    private static MapUiState at(int index) {
        MapUiState b = ScreenFixtures.map();
        return new MapUiState(b.tripTitle, b.unknownCount, b.stops, index,
                b.playing, b.satellite, b.cinema, b.speed);
    }

    /** GPS 정차점(개선문) — GPS 배지, 빼기 없음, extra 없음. */
    @Test
    public void sheet_gpsStop() {
        MapRenderer.renderSheet(binding, at(0));
        harness.captureWrapContentHeight(binding.getRoot(), "map_sheet_gps.png");
    }

    /** AI 근사 위치(루브르) — 근사 배지 + 빼기 버튼이 뜬다. */
    @Test
    public void sheet_aiStop() {
        MapRenderer.renderSheet(binding, at(3));
        harness.captureWrapContentHeight(binding.getRoot(), "map_sheet_ai.png");
    }

    /** 추가 사진이 있는 정차점(에펠탑, +4장) + 재생 중 아이콘. */
    @Test
    public void sheet_playingWithExtras() {
        MapUiState b = at(1);
        MapUiState playing = new MapUiState(b.tripTitle, b.unknownCount, b.stops, b.activeIndex,
                true, b.satellite, b.cinema, b.speed);

        MapRenderer.renderSheet(binding, playing);
        harness.captureWrapContentHeight(binding.getRoot(), "map_sheet_playing.png");
    }
}
