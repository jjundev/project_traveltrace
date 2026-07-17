package com.traveltrace.app.data;

import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Inject;

/**
 * 빈 스텁 (A1 AC: 핵심 인터페이스가 DI로 주입됨을 충족). Epic D에서 Gemini/OpenAI 실제
 * 구현으로 대체된다. 아직 호출 경로가 없으므로 호출 시 의도적으로 미구현 예외.
 */
public class VisionProviderStub implements VisionProvider {

    @Inject
    public VisionProviderStub() {
    }

    @Override
    public RecognitionResult recognize(byte[] imageJpeg) {
        throw new UnsupportedOperationException("Epic D: GeminiProvider / OpenAiProvider 구현 예정");
    }
}
