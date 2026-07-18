package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionRendererTest {

    private FragmentPhotoSelectionBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void rendersAll18TilesInThreeColumns() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertNotNull(binding.photoGrid.getAdapter());
        assertEquals(18, binding.photoGrid.getAdapter().getItemCount());
        assertEquals(3, ((androidx.recyclerview.widget.GridLayoutManager)
                binding.photoGrid.getLayoutManager()).getSpanCount());
    }

    @Test
    public void countReflectsSelectedTiles() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("선택한 사진 16장", binding.selectCount.getText().toString());
    }

    @Test
    public void countUpdatesAfterToggle() {
        PhotoSelectionUiState toggled = ScreenFixtures.photoSelection().withToggled(0);
        PhotoSelectionRenderer.render(binding, toggled, index -> {});

        assertEquals("선택한 사진 15장", binding.selectCount.getText().toString());
    }

    @Test
    public void periodAndHintUsePrototypeCopy() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("2024. 6. 12 – 6. 15 · 사진 94장", binding.periodLabel.getText().toString());
        assertEquals("최대 100장 · 탭하여 제외", binding.selectHint.getText().toString());
    }

    /**
     * 재사용 경로(어댑터가 이미 붙어있는 상태에서 다시 render)에서 리스너가 최신으로 갱신되는지
     * 검증한다. 어댑터를 실제 화면 크기로 레이아웃해 RecyclerView 가 타일 뷰홀더를 만들게 한 뒤,
     * 첫 번째 타일을 직접 클릭해 어떤 리스너가 반응하는지로 판단한다(HomeScreenshotTest 의
     * attach+measure 방식을 그대로 따른다).
     */
    @Test
    public void reRenderUpdatesTileClickListener() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_TravelTrace);
        FragmentPhotoSelectionBinding localBinding =
                FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(activity));

        FrameLayout host = new FrameLayout(activity);
        host.addView(localBinding.getRoot());
        activity.setContentView(host);

        int w = dp(activity, 390);
        int h = dp(activity, 844);

        int[] clickedA = {-1};
        int[] clickedB = {-1};

        PhotoSelectionRenderer.render(localBinding, ScreenFixtures.photoSelection(),
                index -> clickedA[0] = index);
        layoutHost(host, w, h);

        // 재사용 경로: 어댑터는 그대로, 리스너만 바뀐다.
        PhotoSelectionRenderer.render(localBinding, ScreenFixtures.photoSelection(),
                index -> clickedB[0] = index);
        layoutHost(host, w, h);

        View tileRoot = localBinding.photoGrid.getChildAt(0);
        assertNotNull("그리드 타일이 레이아웃되어 있어야 한다", tileRoot);
        View photoTile = tileRoot.findViewById(R.id.photoTile);
        assertNotNull("photoTile 뷰를 찾을 수 있어야 한다", photoTile);

        photoTile.performClick();

        assertEquals("옛 리스너는 더 이상 호출되면 안 된다", -1, clickedA[0]);
        assertEquals("현재 리스너가 클릭을 받아야 한다", 0, clickedB[0]);
    }

    private void layoutHost(FrameLayout host, int w, int h) {
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
    }

    private int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
