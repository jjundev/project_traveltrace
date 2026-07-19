package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.ViewCinemaOverlayBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 상영 모드 오버레이를 PNG 로 캡처한다(프로토타입 육안 대조 + 골든 회귀).
 * 오버레이는 화면 전체를 덮으므로 고정 390x844dp 프레임으로 잡는다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class CinemaOverlayScreenshotTest {

    private ScreenshotHarness harness;
    private ViewCinemaOverlayBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = ViewCinemaOverlayBinding.inflate(harness.inflater());
    }

    /** 상영 모드 ON, 첫 정차점(개선문 10:12). */
    @Test
    public void cinema_activeStop() {
        MapUiState b = ScreenFixtures.map();
        MapUiState cinema = new MapUiState(b.tripTitle, b.unknownCount, b.stops, 0,
                b.playing, b.satellite, true, b.speed);

        MapRenderer.renderCinema(binding, cinema);
        harness.captureFixedFrame(binding.getRoot(), "map_cinema.png");
    }
}
