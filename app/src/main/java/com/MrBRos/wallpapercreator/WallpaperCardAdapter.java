package com.MrBRos.wallpapercreator;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.MrBRos.wallpapercreator.databinding.ItemWallpaperCardBinding;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Two-column grid of tall rounded wallpaper cards with a badge and like count. */
public class WallpaperCardAdapter extends RecyclerView.Adapter<WallpaperCardAdapter.Holder> {

    private final List<WallpaperCard> items = new ArrayList<>();
    private final Consumer<WallpaperCard> onClick;
    private final Consumer<WallpaperCard> onLongClick;

    public WallpaperCardAdapter(Consumer<WallpaperCard> onClick, Consumer<WallpaperCard> onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    public void submit(List<WallpaperCard> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public List<WallpaperCard> items() {
        return java.util.Collections.unmodifiableList(items);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemWallpaperCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        WallpaperCard card = items.get(position);
        ItemWallpaperCardBinding b = holder.binding;
        boolean liveTilt = card.isFourD() && FourDTileView.isSupported();
        b.fourdTile.setVisibility(liveTilt ? View.VISIBLE : View.GONE);
        if (liveTilt) b.fourdTile.setAsset(card.assetPath);
        if (card.isVideo()) {
            // No Glide transform: it would flatten the animated WebP to a still. The ImageView's
            // centerCrop scale type crops it instead.
            Glide.with(b.thumb).load(card.image).into(b.thumb);
        } else {
            // Bundled images crop around their subject; the user's own files fall back to centre.
            float[] focal = Focal.get(b.thumb.getContext(), card.assetPath);
            Glide.with(b.thumb).load(card.image).transform(new FocalCrop(focal[0], focal[1])).into(b.thumb);
        }
        b.badge.setVisibility(card.badge == null ? View.GONE : View.VISIBLE);
        boolean hot = "Hot".equals(card.badge);
        boolean featured = card.isFeatured();
        android.content.Context ctx = b.getRoot().getContext();
        b.badge.setText(hot ? ctx.getString(R.string.badge_hot)
                : card.isVideo() ? ctx.getString(R.string.badge_motion)
                : card.isFourD() ? ctx.getString(R.string.badge_fourd)
                : featured ? ctx.getString(R.string.badge_featured) : card.badge);
        b.badge.setBackgroundResource(hot || featured ? R.drawable.bg_badge_hot : R.drawable.bg_badge_neutral);
        b.title.setText(card.title);
        b.title.setVisibility(card.title.isEmpty() ? View.GONE : View.VISIBLE);
        b.likes.setVisibility(card.likes < 0 ? View.GONE : View.VISIBLE);
        b.likes.setText(b.getRoot().getContext().getString(R.string.likes_short, card.likes));
        b.getRoot().setOnClickListener(v -> onClick.accept(card));
        b.getRoot().setOnLongClickListener(v -> {
            onLongClick.accept(card);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemWallpaperCardBinding binding;

        Holder(ItemWallpaperCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
