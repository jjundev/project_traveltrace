package com.traveltrace.app.core.model;

/** 위치 출처 구분 (PRD §4.2/§4.4 — GPS 핀 vs AI 근사 핀 시각 구분). */
public enum LocationSource {
    GPS,
    AI_APPROX,
    UNKNOWN
}
