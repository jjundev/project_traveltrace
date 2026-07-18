package com.traveltrace.app.ui.photo;

import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * SELECT 화면의 실제 렌더를 PNG 로 캡처한다(프로토타입 육안 대조). 방식은 HomeScreenshotTest 와
 * 동일: 라이브러리 captureRoboImage() 직접 호출 + 390x844dp 프레임 + NATIVE 그래픽스.
 * 공통 준비/캡처 로직은 {@link ScreenshotHarness} 참고.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class PhotoSelectionScreenshotTest {

    private ScreenshotHarness harness;
    private FragmentPhotoSelectionBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = FragmentPhotoSelectionBinding.inflate(harness.inflater());
    }

    @Test
    public void select_default() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});
        harness.captureFixedFrame(binding.getRoot(), "select_default.png");
    }
}
