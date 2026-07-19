package com.traveltrace.app.ui.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ItemTripCardBinding;

import java.util.ArrayList;
import java.util.List;

/** HOME 여행 카드 목록. hero 는 첫 사진 썸네일, 없으면 id 기반 일러스트로 폴백한다. */
public class TripCardAdapter extends RecyclerView.Adapter<TripCardAdapter.VH> {

    public interface Listener {
        void onTripClick(HomeUiState.TripCard card);

        /**
         * 기본 구현은 아무 것도 하지 않는다 — default 메서드라 이 인터페이스는 여전히
         * 함수형 인터페이스(추상 메서드 1개, onTripClick)라서 기존의 {@code card -> {}}
         * 람다·메서드 레퍼런스 사용처(HomeRendererTest/HomeScreenshotTest, 예전 HomeFragment)가
         * 그대로 컴파일된다. 롱프레스로 삭제 확인을 띄우고 싶은 호출부만 오버라이드한다
         * (finding 3).
         */
        default void onTripLongPress(HomeUiState.TripCard card) {}
    }

    private final List<HomeUiState.TripCard> items = new ArrayList<>();
    private Listener listener;

    public TripCardAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<HomeUiState.TripCard> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    /** heroPhotoUri 가 없을 때만 쓰는 폴백 일러스트(픽스처·썸네일 실패). */
    @DrawableRes
    private static int fallbackHero(String id) {
        return "paris".equals(id) ? R.drawable.hero_trip_paris : R.drawable.hero_trip_jeju;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTripCardBinding b = ItemTripCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        HomeUiState.TripCard card = items.get(position);
        if (card.heroPhotoUri == null) {
            com.bumptech.glide.Glide.with(holder.b.tripHero).clear(holder.b.tripHero);
            holder.b.tripHero.setImageResource(fallbackHero(card.id));
        } else {
            com.bumptech.glide.Glide.with(holder.b.tripHero)
                    .load(card.heroPhotoUri)
                    .centerCrop()
                    .placeholder(fallbackHero(card.id))
                    .error(fallbackHero(card.id))
                    .into(holder.b.tripHero);
        }
        holder.b.tripTitle.setText(card.title);
        holder.b.tripMeta.setText(card.meta);
        holder.b.tripLocation.setVisibility(card.locationLabel == null ? View.GONE : View.VISIBLE);
        holder.b.tripLocation.setText(card.locationLabel);
        holder.b.tripCard.setOnClickListener(v -> listener.onTripClick(card));
        // 삭제는 롱프레스로만 연다 — 멀티셀렉트·스와이프 삭제 없이 최소한으로(finding 3).
        holder.b.tripCard.setOnLongClickListener(v -> {
            listener.onTripLongPress(card);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTripCardBinding b;

        VH(ItemTripCardBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
