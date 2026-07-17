package com.traveltrace.app.core.model;

/**
 * AI 비전 인식 결과.
 *
 * PRD §4.3 스키마 {landmarkName, city, country, confidence} 를 구현하는 plan 채택 타입명이다
 * (PRD에는 타입명이 없고 필드만 정의됨). 좌표(lat/lng)는 AI가 만들지 않으며, 이후 지오코딩
 * 단계(Epic E)에서 별도로 결정한다.
 */
public final class RecognitionResult {
    public final String landmarkName;
    public final String city;
    public final String country;
    public final double confidence;

    public RecognitionResult(String landmarkName, String city, String country, double confidence) {
        this.landmarkName = landmarkName;
        this.city = city;
        this.country = country;
        this.confidence = confidence;
    }
}
