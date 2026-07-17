package com.traveltrace.app.domain;

import com.traveltrace.app.core.model.RecognitionResult;

/**
 * Gemini(메인) / OpenAI(폴백) 공통 추상화 (PRD §4.3). 좌표는 반환하지 않는다 —
 * 이름/도시만 인식하고 좌표화는 {@link Geocoder}가 담당한다. 실제 구현은 Epic D.
 *
 * 입력은 PRD §5 전송 파이프라인에 따라 다운스케일·JPEG 재인코딩된 바이트다. 블로킹 호출이며
 * 백그라운드 스레드(Epic G의 ExecutorService)에서 실행한다.
 */
public interface VisionProvider {
    RecognitionResult recognize(byte[] imageJpeg) throws Exception;
}
