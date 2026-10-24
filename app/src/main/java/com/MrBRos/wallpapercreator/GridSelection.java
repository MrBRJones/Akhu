package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Gallery-style multi-select for one grid: a long-press starts it, taps then tick items on and
 * off, and {@link MainActivity} swaps its top and bottom bars for "N selected" and Share / Delete.
 */
final class GridSelection<T> implements MainActivity.SelectionHost {

    /** What the grid's items are and what Share and Delete do with them. */
    interface Actions<T> {
        /** Stable id for an item, so ticks survive the grid reloading. */
        String key(T item);

        boolean canSelect(T item);

        /** The file Share sends for an item, or null if it can't be shared. */
        @Nullable
        File file(T item);

        /** Removes one item; runs off the main thread. */
        void delete(Context app, T item);

        /** Refills the grid after a delete. */
        void reload();
    }

    private final Fragment owner;
    private final RecyclerView.Adapter<?> adapter;
    private final Supplier<List<T>> items;
    private final Actions<T> actions;
    private final Set<String> keys = new LinkedHashSet<>();
    private boolean active;

    GridSelection(Fragment owner, RecyclerView.Adapter<?> adapter, Supplier<List<T>> items, Actions<T> actions) {
        this.owner = owner;
        this.adapter = adapter;
        this.items = items;
        this.actions = actions;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    boolean isSelected(T item) {
        return keys.contains(actions.key(item));
    }

    boolean canSelect(T item) {
        return actions.canSelect(item);
    }

    /** Long-press: starts selecting with this item, or ticks it if already selecting. False if it can't be picked. */
    boolean onLongPress(T item) {
        if (!actions.canSelect(item)) return false;
        if (!active) {
            active = true;
            keys.add(actions.key(item));
            changed();
        } else {
            toggle(item);
        }
        return true;
    }

    /** Tap: ticks the item while selecting. False when not selecting, so the tap opens it as usual. */
    boolean onTap(T item) {
        if (!active) return false;
        if (actions.canSelect(item)) toggle(item);
        return true;
    }

    private void toggle(T item) {
        String key = actions.key(item);
        if (!keys.remove(key)) keys.add(key);
        changed();
    }

    /** Drops ticks on items that are gone; ends selecting if nothing selectable is left. */
    void retain() {
        if (!active) return;
        Set<String> present = new LinkedHashSet<>();
        boolean any = false;
        for (T item : items.get()) {
            if (!actions.canSelect(item)) continue;
            any = true;
            present.add(actions.key(item));
        }
        keys.retainAll(present);
        if (!any) endSelection();
        else changed();
    }

    private List<T> selected() {
        List<T> out = new ArrayList<>();
        for (T item : items.get()) if (keys.contains(actions.key(item))) out.add(item);
        return out;
    }

    @Override
    public int selectedCount() {
        return keys.size();
    }

    @Override
    public int selectableCount() {
        int n = 0;
        for (T item : items.get()) if (actions.canSelect(item)) n++;
        return n;
    }

    @Override
    public void setAllSelected(boolean all) {
        keys.clear();
        if (all) for (T item : items.get()) if (actions.canSelect(item)) keys.add(actions.key(item));
        changed();
    }

    @Override
    public void endSelection() {
        if (!active) return;
        active = false;
        keys.clear();
        changed();
    }

    @Override
    public void shareSelected() {
        Context context = owner.requireContext();
        ArrayList<Uri> uris = new ArrayList<>();
        boolean images = true, videos = true;
        for (T item : selected()) {
            File f = actions.file(item);
            if (f == null || !f.exists()) continue;
            boolean video = f.getName().endsWith(".mp4");
            images &= !video;
            videos &= video;
            uris.add(ImageExporter.getShareUri(context, f));
        }
        if (uris.isEmpty()) {
            Toast.makeText(context, R.string.select_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType(images ? "image/*" : videos ? "video/*" : "*/*");
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        owner.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)));
    }

    @Override
    public void deleteSelected() {
        List<T> doomed = selected();
        Context context = owner.requireContext();
        if (doomed.isEmpty()) {
            Toast.makeText(context, R.string.select_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = context.getApplicationContext();
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getResources().getQuantityString(R.plurals.delete_selected_title,
                        doomed.size(), doomed.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    for (T item : doomed) actions.delete(app, item);
                    if (owner.getActivity() == null) return;
                    owner.requireActivity().runOnUiThread(() -> {
                        endSelection();
                        actions.reload();
                    });
                }, "DeleteSelected").start())
                .show();
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void changed() {
        adapter.notifyDataSetChanged();
        if (owner.getActivity() instanceof MainActivity) {
            ((MainActivity) owner.getActivity()).onSelectionChanged(this);
        }
    }
}
