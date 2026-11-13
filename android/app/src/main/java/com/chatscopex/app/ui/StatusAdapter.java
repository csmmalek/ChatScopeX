package com.chatscopex.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.StatusItem;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.Profiles;
import com.chatscopex.app.util.TimeFormat;

import java.util.ArrayList;
import java.util.List;

public class StatusAdapter extends RecyclerView.Adapter<StatusAdapter.VH> {
    public interface Listener { void onOpen(String ownerUid); }

    private final Listener listener;
    private List<StatusFragment.OwnerGroup> items = new ArrayList<>();

    public StatusAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<StatusFragment.OwnerGroup> list) {
        items = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_status, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        Avatars.ring(h.ring);
        StatusFragment.OwnerGroup g = items.get(position);
        Profiles.bind(h.avatar, h.name, g.owner, () -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < items.size()) notifyItemChanged(pos);
        });
        StatusItem latest = g.items.get(0);
        h.time.setText(c.getResources().getQuantityString(R.plurals.status_count, g.items.size(), g.items.size())
                + "  ·  " + TimeFormat.format(c, latest.createdMillis));
        h.itemView.setOnClickListener(v -> listener.onOpen(g.owner));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final View ring;
        final TextView name, time;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            ring = v.findViewById(R.id.ring);
            name = v.findViewById(R.id.name);
            time = v.findViewById(R.id.time);
        }
    }
}
