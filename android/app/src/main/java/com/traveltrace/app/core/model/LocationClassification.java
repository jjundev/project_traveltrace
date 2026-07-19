package com.traveltrace.app.core.model;

/** 사진 1장의 위치 판정 결과 (PRD §4.6 분류 3종 + 시각 없음). */
public enum LocationClassification {
    /** 좌표·시각이 모두 있어 경로에 그려진다. */
    PLACED,
    /** 이름은 알아냈지만 좌표화 실패. 지도 제외. S3 에서 생긴다. */
    NAME_ONLY,
    /** GPS·AI 모두 실패. 위치 미상 그룹. */
    UNKNOWN,
    /** 좌표는 있으나 촬영 시각이 없어 경로 순서에서 제외된다. */
    NO_TIME
}
