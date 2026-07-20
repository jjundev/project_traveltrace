package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.data.media.AlbumBucket;
import com.traveltrace.app.databinding.SheetAlbumPickerBinding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AlbumPickerSheetTest {

    private SheetAlbumPickerBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = SheetAlbumPickerBinding.inflate(LayoutInflater.from(ctx));
    }

    /** 첫 행(bucketId=null)은 호출부가 미리 앞에 붙인 "전체 사진" 합성 항목이다. */
    private static List<AlbumBucket> threeRows() {
        return Arrays.asList(
                new AlbumBucket(null, "전체 사진", 150),
                new AlbumBucket("7", "카메라", 120),
                new AlbumBucket("9", "카카오톡", 30));
    }

    @Test
    public void addsOneRowPerAlbum() {
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null, (id, name) -> {});
        assertEquals(3, binding.albumRows.getChildCount());
    }

    @Test
    public void rowShowsNameAndCount() {
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null, (id, name) -> {});

        View row = binding.albumRows.getChildAt(1);
        TextView name = row.findViewById(R.id.albumRowName);
        TextView count = row.findViewById(R.id.albumRowCount);

        assertEquals("카메라", name.getText().toString());
        assertEquals("120장", count.getText().toString());
    }

    @Test
    public void rebindDoesNotDuplicateRows() {
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null, (id, name) -> {});
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null, (id, name) -> {});
        assertEquals(3, binding.albumRows.getChildCount());
    }

    @Test
    public void clickingARowInvokesTheListenerWithThatAlbum() {
        AtomicReference<String> clickedId = new AtomicReference<>("unset");
        AtomicReference<String> clickedName = new AtomicReference<>();

        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null,
                (id, name) -> {
                    clickedId.set(id);
                    clickedName.set(name);
                });

        binding.albumRows.getChildAt(1).performClick();

        assertEquals("7", clickedId.get());
        assertEquals("카메라", clickedName.get());
    }

    @Test
    public void clickingTheAllPhotosRowReportsANullBucketId() {
        AtomicReference<String> clickedId = new AtomicReference<>("unset");

        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null,
                (id, name) -> clickedId.set(id));

        binding.albumRows.getChildAt(0).performClick();

        assertEquals(null, clickedId.get());
    }

    @Test
    public void currentlySelectedAlbumRowIsHighlighted() {
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), "7", (id, name) -> {});
        Context ctx = binding.getRoot().getContext();

        TextView selectedName = binding.albumRows.getChildAt(1).findViewById(R.id.albumRowName);
        TextView unselectedName = binding.albumRows.getChildAt(0).findViewById(R.id.albumRowName);

        assertEquals(ContextCompat.getColor(ctx, R.color.text_brand),
                selectedName.getCurrentTextColor());
        assertEquals(ContextCompat.getColor(ctx, R.color.text_primary),
                unselectedName.getCurrentTextColor());
    }

    @Test
    public void nullSelectedBucketIdHighlightsTheAllPhotosRow() {
        AlbumPickerSheetFragment.bindContent(binding, threeRows(), null, (id, name) -> {});
        Context ctx = binding.getRoot().getContext();

        TextView allPhotosName = binding.albumRows.getChildAt(0).findViewById(R.id.albumRowName);

        assertEquals(ContextCompat.getColor(ctx, R.color.text_brand),
                allPhotosName.getCurrentTextColor());
    }
}
