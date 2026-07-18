package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnalysisViewModelTest {

    @Test
    public void cityIsSuppliedForTheTimezoneSheet() {
        // 시트 도시명이 시드(파리)로 공급되는지 — 씨앗이 리포지토리로 바뀌어도 이 자리가 seam.
        assertEquals("파리", new AnalysisViewModel().city());
    }
}
