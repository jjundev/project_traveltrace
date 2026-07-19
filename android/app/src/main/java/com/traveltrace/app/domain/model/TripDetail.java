package com.traveltrace.app.domain.model;

import java.util.Collections;
import java.util.List;

/** MAP 화면이 필요한 저장 여행 1건. AI·EXIF 재실행 없이 이것만으로 재생한다. */
public class TripDetail {
    public String id;
    public String name;
    public String timeZoneId;
    public List<StopRow> stops = Collections.emptyList();
    public int unknownCount;
}
