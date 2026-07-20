package com.traveltrace.app.data;

import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Inject;

/**
 * 빈 스텁 (Epic D 에서 Gemini/OpenAI 실제 구현으로 대체). 아직 호출 경로가 없으므로 호출 시
 * 의도적으로 미구현 예외.
 *
 * <p>미구현이어도 {@link AnalysisCostLog} 에는 <b>먼저</b> 기록한다 — "저장 여행을 열면
 * vision 호출이 0회"라는 S8 수용 기준이 실제 구현으로 바뀐 뒤에도 계속 검증되려면, 호출을
 * 세는 지점이 구현이 아니라 이 경계에 있어야 한다.
 */
public class VisionProviderStub implements VisionProvider {

    private final AnalysisCostLog costLog;

    @Inject
    public VisionProviderStub(AnalysisCostLog costLog) {
        this.costLog = costLog;
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) {
        costLog.recordVisionCall();
        throw new UnsupportedOperationException("Epic D: GeminiProvider / OpenAiProvider 구현 예정");
    }
}
