package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnalysisUiStateTest {

    @Test
    public void percentIsRoundedFromAnalyzedOverTotal() {
        assertEquals(50, new AnalysisUiState(41, 82, false, "a.jpg", 0, 0).progressPercent());
        assertEquals(100, new AnalysisUiState(82, 82, true, "b.jpg", 6, 5).progressPercent());
    }

    @Test
    public void zeroTotalDoesNotDivideByZero() {
        assertEquals(0, new AnalysisUiState(0, 0, false, "", 0, 0).progressPercent());
    }
}
