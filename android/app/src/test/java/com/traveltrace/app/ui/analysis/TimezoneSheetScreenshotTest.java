package com.traveltrace.app.ui.analysis;

import com.traveltrace.app.databinding.SheetTimezoneBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 타임존 확인 시트 콘텐츠를 PNG 로 캡처한다(프로토타입 육안 대조). 시트는 모달이라 화면 전체를
 * 덮지 않으므로, 콘텐츠를 390dp 폭·wrap_content 로 렌더해 잡는다({@link
 * ScreenshotHarness#captureWrapContentHeight} — 다른 스크린샷 테스트의 고정 390x844dp 프레임과의
 * 유일한 차이점).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class TimezoneSheetScreenshotTest {

    @Test
    public void timezoneSheet_paris() {
        ScreenshotHarness harness = ScreenshotHarness.create();
        SheetTimezoneBinding binding = SheetTimezoneBinding.inflate(harness.inflater());
        TimezoneSheetFragment.bindContent(binding, ScreenFixtures.cityLabel());

        harness.captureWrapContentHeight(binding.getRoot(), "sheet_timezone.png");
    }
}
