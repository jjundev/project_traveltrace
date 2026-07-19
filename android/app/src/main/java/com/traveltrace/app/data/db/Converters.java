package com.traveltrace.app.data.db;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;

/** enum ↔ String. 이름으로 저장해야 enum 순서를 바꿔도 기존 행이 안 깨진다. */
public class Converters {

    @TypeConverter
    @Nullable
    public static String fromSource(@Nullable LocationSource source) {
        return source == null ? null : source.name();
    }

    @TypeConverter
    @Nullable
    public static LocationSource toSource(@Nullable String name) {
        return name == null ? null : LocationSource.valueOf(name);
    }

    @TypeConverter
    @Nullable
    public static String fromClassification(@Nullable LocationClassification c) {
        return c == null ? null : c.name();
    }

    @TypeConverter
    @Nullable
    public static LocationClassification toClassification(@Nullable String name) {
        return name == null ? null : LocationClassification.valueOf(name);
    }
}
