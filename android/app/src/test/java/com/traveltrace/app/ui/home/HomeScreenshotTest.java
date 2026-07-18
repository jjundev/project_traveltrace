package com.traveltrace.app.ui.home;

import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.common.ToastPresenter;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * HOME 화면의 실제 렌더를 PNG 로 캡처한다(디자인 프로토타입과 육안 대조용).
 *
 * <p>Roborazzi 의 Gradle 플러그인은 Kotlin Gradle 플러그인을 요구하므로 적용하지 않는다.
 * 대신 라이브러리의 captureRoboImage() 를 직접 호출하고, 기록 여부·출력 경로는
 * build.gradle 의 시스템 프로퍼티(roborazzi.test.record / roborazzi.output.dir)로 준다.
 *
 * <p>프레임은 프로토타입 기준 390x844dp, 그래픽스는 NATIVE 모드여야 실제 픽셀이 그려진다.
 * 공통 준비/캡처 로직은 {@link ScreenshotHarness} 참고. Renderer 는 static 이라
 * Fragment/Hilt 없이 직접 호출한다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class HomeScreenshotTest {

    private ScreenshotHarness harness;
    private FragmentHomeBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = FragmentHomeBinding.inflate(harness.inflater());
    }

    @Test
    public void home_withTrips() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        harness.captureFixedFrame(binding.getRoot(), "home_trips.png");
    }

    @Test
    public void home_empty() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        harness.captureFixedFrame(binding.getRoot(), "home_empty.png");
    }

    @Test
    public void home_withToast() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        ToastPresenter.show(binding.getRoot(), "데모에선 파리 여행만 열려요");
        harness.captureFixedFrame(binding.getRoot(), "home_toast.png");
    }
}
