package com.traveltrace.app.ui.map;

import com.traveltrace.app.databinding.SheetUnknownPhotosBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 위치 미상 드로어를 PNG 로 캡처한다(프로토타입 육안 대조 + 골든 회귀).
 * 시트는 모달이라 wrap_content 높이로 잡는다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class UnknownPhotosSheetScreenshotTest {

    @Test
    public void unknownDrawer_fiveThumbs() {
        ScreenshotHarness harness = ScreenshotHarness.create();
        SheetUnknownPhotosBinding binding =
                SheetUnknownPhotosBinding.inflate(harness.inflater());

        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());

        harness.captureWrapContentHeight(binding.getRoot(), "sheet_unknown.png");
    }
}
