package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class HomeRendererTest {

    private Context ctx;
    private FragmentHomeBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentHomeBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void tripsState_showsListAndHidesEmptyBlock() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});

        assertEquals(View.VISIBLE, binding.tripList.getVisibility());
        assertEquals(View.GONE, binding.emptyGroup.getVisibility());
        assertNotNull(binding.tripList.getAdapter());
        assertEquals(2, binding.tripList.getAdapter().getItemCount());
    }

    @Test
    public void emptyState_showsEmptyBlockAndHidesList() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        assertEquals(View.GONE, binding.tripList.getVisibility());
        assertEquals(View.VISIBLE, binding.emptyGroup.getVisibility());
    }

    @Test
    public void ctaIsAlwaysVisibleInBothStates() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());

        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());
    }

    @Test
    public void emptyBlockUsesPrototypeCopy() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        TextView title = binding.emptyTitle;
        assertEquals("첫 여행을 만들어 보세요", title.getText().toString());
    }

    @Test
    public void toastPillStartsHiddenAndShowsMessage() {
        assertEquals(View.GONE, binding.toastPill.getVisibility());

        com.traveltrace.app.ui.common.ToastPresenter.show(
                binding.getRoot(), "데모에선 파리 여행만 열려요");

        assertEquals(View.VISIBLE, binding.toastPill.getVisibility());
        assertEquals("데모에선 파리 여행만 열려요", binding.toastPill.getText().toString());
    }

    @Test
    public void toastAutoHidesAfterDelay() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(binding.getRoot());

        com.traveltrace.app.ui.common.ToastPresenter.show(
                binding.getRoot(), "데모에선 파리 여행만 열려요");
        assertEquals(View.VISIBLE, binding.toastPill.getVisibility());

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertEquals(View.GONE, binding.toastPill.getVisibility());
    }

    @Test
    public void rapidSecondToastCancelsPriorHide() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(binding.getRoot());

        com.traveltrace.app.ui.common.ToastPresenter.show(binding.getRoot(), "토스트 A");
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        com.traveltrace.app.ui.common.ToastPresenter.show(binding.getRoot(), "토스트 B");
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);

        assertEquals(View.VISIBLE, binding.toastPill.getVisibility());
        assertEquals("토스트 B", binding.toastPill.getText().toString());
    }

    @Test
    public void cancelAfterDetachPreventsLateHide() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(binding.getRoot());

        View root = binding.getRoot();
        com.traveltrace.app.ui.common.ToastPresenter.show(root, "데모에선 파리 여행만 열려요");
        assertEquals(View.VISIBLE, binding.toastPill.getVisibility());

        // onDestroyView() 이전에 AndroidX Fragment가 뷰를 컨테이너에서 detach하는 상황을 재현.
        ((ViewGroup) root.getParent()).removeView(root);

        com.traveltrace.app.ui.common.ToastPresenter.cancel(root);

        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);

        assertEquals(
                "취소 후에는 태그에 대기 중인 콜백이 남아있으면 안 된다",
                null, binding.toastPill.getTag(R.id.toastPill));
        assertEquals(
                "취소된 숨김이 detach 이후에도 뒤늦게 실행되면 안 된다",
                View.VISIBLE, binding.toastPill.getVisibility());
    }
}
