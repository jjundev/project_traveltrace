package com.traveltrace.app.ui;

import static org.junit.Assert.assertTrue;

import androidx.appcompat.widget.AppCompatImageView;

import com.traveltrace.app.databinding.ViewMapTopBarBinding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Guards the fix in {@link ScreenshotHarness#create()}: the harness must host captures in an
 * AppCompat activity so its {@link ScreenshotHarness#inflater()} installs AppCompat's
 * view-inflater factory. Without that, {@code <ImageView>} in layouts inflates as a plain
 * {@code android.widget.ImageView} and {@code app:tint} is silently ignored (see
 * ScreenshotHarness's class doc). This test inflates a real layout (the MAP top bar) and asserts
 * the back-arrow {@code <ImageView>} came back as an {@link AppCompatImageView} — the concrete,
 * mechanical signal that AppCompat inflation is active, independent of any pixel comparison.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class ScreenshotHarnessTest {

    @Test
    public void inflater_upgradesImageViewToAppCompatImageView() {
        ScreenshotHarness harness = ScreenshotHarness.create();
        ViewMapTopBarBinding binding = ViewMapTopBarBinding.inflate(harness.inflater());

        assertTrue(
                "expected the <ImageView> back arrow to be upgraded to AppCompatImageView by "
                        + "AppCompat's view-inflater factory; got "
                        + binding.mapBack.getClass().getName() + " instead -- app:tint would be "
                        + "silently ignored on a plain android.widget.ImageView",
                binding.mapBack instanceof AppCompatImageView);
    }
}
