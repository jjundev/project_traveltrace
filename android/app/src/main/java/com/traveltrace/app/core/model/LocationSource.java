package com.traveltrace.app.core.model;

/** 위치 좌표의 출처 (PRD §4.2 우선순위 규칙). */
public enum LocationSource {
    /** EXIF GPS 에서 직접 읽은 좌표. 가장 정확하고 AI 호출이 없다. */
    GPS,
    /** AI 이름 추론 + 지오코딩으로 얻은 근사 위치. S3 에서 생긴다. */
    AI,
    /** 좌표를 얻지 못함. */
    NONE
}
