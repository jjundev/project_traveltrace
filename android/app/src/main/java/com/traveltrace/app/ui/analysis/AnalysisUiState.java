package com.traveltrace.app.ui.analysis;

/** ANALYZE 화면이 렌더할 불변 상태. done 이면 완료형 카드 + CTA 로 전환된다. */
public final class AnalysisUiState {

    public final int analyzed;
    public final int total;
    public final boolean done;
    public final String recognizedName;
    public final int routeCount;
    public final int unknownCount;

    public AnalysisUiState(int analyzed, int total, boolean done, String recognizedName,
                           int routeCount, int unknownCount) {
        this.analyzed = analyzed;
        this.total = total;
        this.done = done;
        this.recognizedName = recognizedName;
        this.routeCount = routeCount;
        this.unknownCount = unknownCount;
    }

    public int progressPercent() {
        if (total <= 0) return 0;
        return Math.round(analyzed * 100f / total);
    }
}
