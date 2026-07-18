package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetTimezoneBinding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TimezoneSheetTest {

    private SheetTimezoneBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = SheetTimezoneBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void interpolatesCityIntoTitleBodyAndConfirmButton() {
        TimezoneSheetFragment.bindContent(binding, "파리");

        assertEquals("이 여행, 파리 기준이 맞죠?", binding.tzTitle.getText().toString());
        assertEquals("네, 파리 기준이 맞아요", binding.tzConfirm.getText().toString());
        assertEquals("사진 시각을 파리 시간대로 맞춰 정확한 순서로 정렬해요. 다른 도시라면 바꿔 주세요.",
                binding.tzBody.getText().toString());
    }

    @Test
    public void changeCityButtonUsesFixedCopy() {
        TimezoneSheetFragment.bindContent(binding, "파리");
        assertEquals("다른 도시 선택", binding.tzChangeCity.getText().toString());
    }
}
