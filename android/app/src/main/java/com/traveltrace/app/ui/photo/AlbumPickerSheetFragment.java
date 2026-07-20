package com.traveltrace.app.ui.photo;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.traveltrace.app.R;
import com.traveltrace.app.data.media.AlbumBucket;
import com.traveltrace.app.databinding.SheetAlbumPickerBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 사진첩(앨범) 선택 드로어 — SELECT 화면 상단 드롭다운이 연다. */
public class AlbumPickerSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "album_sheet";

    private static final String ARG_IDS = "ids";
    private static final String ARG_NAMES = "names";
    private static final String ARG_COUNTS = "counts";
    private static final String ARG_SELECTED = "selected";

    /**
     * 호스트 Fragment 가 구현한다. bucketId 가 null 이면 "전체 사진"이 선택된 것 —
     * {@link PhotoSelectionViewModel#selectAlbum} 의 계약과 그대로 맞물린다.
     */
    public interface Listener {
        void onAlbumSelected(@Nullable String bucketId, String displayName);
    }

    private SheetAlbumPickerBinding binding;
    private Listener listener;

    /**
     * "전체 사진" 합성 항목을 포함한 전체 목록을 넘긴다 — 그 라벨은 한국어 문자열
     * 리소스가 필요한 UI 결정이라 호출부(Fragment)가 붙이고, 이 시트는 순서대로 그릴
     * 뿐이다. Bundle 은 커스텀 객체보다 원시 배열이 이 코드베이스의 관례다
     * (UnknownPhotosSheetFragment 참고).
     */
    public static AlbumPickerSheetFragment newInstance(List<AlbumBucket> albums,
                                                       @Nullable String selectedBucketId) {
        String[] ids = new String[albums.size()];
        String[] names = new String[albums.size()];
        int[] counts = new int[albums.size()];
        for (int i = 0; i < albums.size(); i++) {
            ids[i] = albums.get(i).bucketId;
            names[i] = albums.get(i).displayName;
            counts[i] = albums.get(i).count;
        }

        AlbumPickerSheetFragment f = new AlbumPickerSheetFragment();
        Bundle args = new Bundle();
        args.putStringArray(ARG_IDS, ids);
        args.putStringArray(ARG_NAMES, names);
        args.putIntArray(ARG_COUNTS, counts);
        args.putString(ARG_SELECTED, selectedBucketId);
        f.setArguments(args);
        return f;
    }

    /**
     * 콘텐츠 렌더만 분리 — 다이얼로그 없이 테스트할 수 있다. 행 개수가 기기마다 달라
     * UnknownPhotosSheetFragment 처럼 코드로 뷰를 쌓는다(고정 그리드가 아니라 목록이라
     * item_album_row.xml 을 inflate 해서 붙인다).
     */
    public static void bindContent(SheetAlbumPickerBinding binding, List<AlbumBucket> albums,
                                   @Nullable String selectedBucketId, Listener onRowClicked) {
        Context ctx = binding.getRoot().getContext();
        binding.albumRows.removeAllViews();

        for (AlbumBucket album : albums) {
            View row = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_album_row, binding.albumRows, false);
            TextView name = row.findViewById(R.id.albumRowName);
            TextView count = row.findViewById(R.id.albumRowCount);

            name.setText(album.displayName);
            count.setText(ctx.getString(R.string.select_album_count, album.count));

            // 현재 앨범만 브랜드 색으로 강조 (프로토타입 tab/speed pill 과 같은 규칙 —
            // 이 코드베이스는 선택 상태를 폰트 굵기가 아니라 색으로만 표현한다).
            boolean selected = Objects.equals(selectedBucketId, album.bucketId);
            name.setTextColor(ContextCompat.getColor(ctx,
                    selected ? R.color.text_brand : R.color.text_primary));

            row.setOnClickListener(
                    v -> onRowClicked.onAlbumSelected(album.bucketId, album.displayName));
            binding.albumRows.addView(row);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof Listener) {
            listener = (Listener) getParentFragment();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetAlbumPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String[] ids = requireArguments().getStringArray(ARG_IDS);
        String[] names = requireArguments().getStringArray(ARG_NAMES);
        int[] counts = requireArguments().getIntArray(ARG_COUNTS);
        String selected = requireArguments().getString(ARG_SELECTED);

        List<AlbumBucket> albums = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            albums.add(new AlbumBucket(ids[i], names[i], counts[i]));
        }

        bindContent(binding, albums, selected, (bucketId, displayName) -> {
            if (listener != null) listener.onAlbumSelected(bucketId, displayName);
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
