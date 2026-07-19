package com.traveltrace.app.ui.photo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ItemPhotoTileBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 3열 사진 그리드. contentUri 가 있으면 Glide 로 썸네일을, 없으면(픽스처) 톤 색을 그린다.
 * 선택 링/불투명도는 프로토타입 t.style 을 그대로 옮긴 것:
 * 선택 = 3dp 파란 링 + alpha 1, 해제 = 1dp 옅은 테두리 + alpha .5
 */
public class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.VH> {

    public interface Listener {
        void onTileClick(int index);
    }

    private static final float ALPHA_UNSELECTED = 0.5f;

    private final List<PhotoSelectionUiState.Tile> items = new ArrayList<>();
    private Listener listener;

    public PhotoGridAdapter(Listener listener) {
        this.listener = listener;
    }

    /** 재사용 경로에서 Renderer 가 현재 리스너로 갱신한다 (final 이면 옛 리스너가 남는다). */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<PhotoSelectionUiState.Tile> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemPhotoTileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PhotoSelectionUiState.Tile tile = items.get(position);
        ItemPhotoTileBinding b = holder.b;
        android.content.Context ctx = b.getRoot().getContext();

        b.photoTile.setCardBackgroundColor(tile.toneColor);
        // 실제 사진이 있으면 썸네일로 덮고, 없으면(픽스처·프리뷰) 톤 색만 남긴다.
        if (tile.contentUri == null) {
            com.bumptech.glide.Glide.with(b.tileImage).clear(b.tileImage);
            b.tileImage.setImageDrawable(null);
            b.tileImage.setVisibility(View.GONE);
        } else {
            b.tileImage.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(b.tileImage)
                    .load(tile.contentUri)
                    .centerCrop()
                    .into(b.tileImage);
        }
        b.photoTile.setAlpha(tile.selected ? 1f : ALPHA_UNSELECTED);
        b.photoTile.setStrokeColor(ContextCompat.getColor(ctx,
                tile.selected ? R.color.fill_brand : R.color.divider));
        b.photoTile.setStrokeWidth(ctx.getResources().getDimensionPixelSize(tile.selected
                ? R.dimen.photo_tile_stroke_selected
                : R.dimen.photo_tile_stroke_unselected));

        b.tileCheck.setVisibility(tile.selected ? View.VISIBLE : View.GONE);

        if (tile.label == null) {
            b.tileLabel.setVisibility(View.GONE);
        } else {
            b.tileLabel.setVisibility(View.VISIBLE);
            b.tileLabel.setText(tile.label);
        }

        int index = position;
        b.photoTile.setOnClickListener(v -> listener.onTileClick(index));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPhotoTileBinding b;

        VH(ItemPhotoTileBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
