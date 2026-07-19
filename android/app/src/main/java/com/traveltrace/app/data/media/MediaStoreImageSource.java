package com.traveltrace.app.data.media;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.domain.Callback;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 갤러리 이미지 목록. PRD §4.1: Photo Picker 대신 MediaStore 를 쓰는 이유는
 * 캐시·재조회에 필요한 <em>안정적 _ID</em> 때문이다.
 *
 * <p>커서에서 뽑는 건 long/문자열뿐이라 수천 장도 수 MB 미만 — 페이징 없이 1회 전량 읽고,
 * 비싼 비트맵은 Glide 가 바인드 시점에 지연 로딩한다.
 */
@Singleton
public class MediaStoreImageSource {

    private static final String[] PROJECTION = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN};

    private final Context context;
    private final AppExecutors executors;

    @Inject
    public MediaStoreImageSource(@ApplicationContext Context context, AppExecutors executors) {
        this.context = context;
        this.executors = executors;
    }

    /** 최신 촬영순으로 최대 limit 장. 권한이 없으면 빈 목록이 온다(예외를 던지지 않는다). */
    public void loadRecent(int limit, Callback<List<GalleryImage>> callback) {
        executors.io().execute(() -> {
            List<GalleryImage> images = query(limit);
            executors.mainThread().execute(() -> callback.onResult(images));
        });
    }

    /**
     * 이미 알고 있는 식별자 집합만 읽는다(ANALYZE 가 쓴다 — finding 5). SELECT 에서 이미
     * 확정된 선택이라 "최근 N 장 전체를 훑어 그중에서 고른다"가 아니라 처음부터
     * {@code _ID IN (...)} 로 걸러 쿼리한다 — 갤러리가 수만 장이어도 선택한 만큼만 읽는다.
     * ids 가 비어 있으면 쿼리 자체를 생략하고 빈 목록을 돌려준다.
     */
    public void loadByIds(List<Long> ids, Callback<List<GalleryImage>> callback) {
        executors.io().execute(() -> {
            List<GalleryImage> images = queryByIds(ids);
            executors.mainThread().execute(() -> callback.onResult(images));
        });
    }

    private List<GalleryImage> query(int limit) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC";

        try (Cursor cursor = context.getContentResolver()
                .query(collection, PROJECTION, null, null, sort)) {
            return readRows(cursor, limit);
        } catch (SecurityException denied) {
            // 권한이 아직 없거나 철회된 상태. 빈 목록으로 조용히 끝낸다 — 안내는 UI 소관.
            return new ArrayList<>();
        }
    }

    private List<GalleryImage> queryByIds(List<Long> ids) {
        if (ids.isEmpty()) return new ArrayList<>();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        StringBuilder selection = new StringBuilder(MediaStore.Images.Media._ID).append(" IN (");
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) selection.append(',');
            selection.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        selection.append(')');

        try (Cursor cursor = context.getContentResolver()
                .query(collection, PROJECTION, selection.toString(), args, sort)) {
            return readRows(cursor, Integer.MAX_VALUE);
        } catch (SecurityException denied) {
            return new ArrayList<>();
        }
    }

    private List<GalleryImage> readRows(@Nullable Cursor cursor, int limit) {
        List<GalleryImage> images = new ArrayList<>();
        if (cursor == null) return images;

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

        while (cursor.moveToNext() && images.size() < limit) {
            long id = cursor.getLong(idCol);
            long taken = cursor.isNull(takenCol) ? 0L : cursor.getLong(takenCol);
            images.add(new GalleryImage(
                    id,
                    ContentUris.withAppendedId(collection, id),
                    cursor.getString(nameCol),
                    // 0 은 "촬영 시각 모름"이다 — 1970년으로 저장하면 정렬이 망가진다.
                    taken > 0L ? taken : null));
        }
        return images;
    }
}
