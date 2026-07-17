package com.traveltrace.app.core.model;

import java.util.Collections;
import java.util.List;

/** 여행 저장 단위 (PRD §4.7). 상세 Room 스키마는 Epic I에서 구체화. */
public final class Trip {
    public final String id;
    public final String name;
    public final List<PhotoItem> photos;

    public Trip(String id, String name, List<PhotoItem> photos) {
        this.id = id;
        this.name = name;
        this.photos = photos != null ? photos : Collections.emptyList();
    }
}
