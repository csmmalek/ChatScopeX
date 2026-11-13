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
import com.chatscopex.app.model.GroupSummary;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.Previews;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.TimeFormat;

import java.util.ArrayList;
import java.util.List;

public class GroupsAdapter extends RecyclerView.Adapter<GroupsAdapter.VH> {
    public interface Listener { void onOpen(GroupSummary group); }

    private final String myUid;
    private final Listener listener;
    private List<GroupSummary> items = new ArrayList<>();

    public GroupsAdapter(String myUid, Listener listener) {
        this.myUid = myUid;
        this.listener = listener;
    }

    public void submit(List<GroupSummary> list) {
        items = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        GroupSummary g = items.get(position);
        Avatars.setGroup(h.avatar, g.avatar);
        h.name.setText(g.name);
        h.members.setText(c.getResources().getQuantityString(R.plurals.members_count, g.memberCount, g.memberCount));

        String preview = Previews.text(c, g.lastType, g.lastText);
        if (!preview.isEmpty()) {
            if (g.lastSenderId.equals(myUid)) {
                preview = c.getString(R.string.you_prefix, preview);
            } else if (!g.lastSenderId.isEmpty()) {
                String who = ProfileCache.peek(g.lastSenderId) != null
                        ? ProfileCache.peek(g.lastSenderId).displayName : c.getString(R.string.unknown_user);
                preview = who + ": " + preview;
            }
        }
        h.last.setText(preview);
        h.time.setText(TimeFormat.format(c, g.lastTimeMillis));
        h.itemView.setOnClickListener(v -> listener.onOpen(g));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name, members, last, time;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            members = v.findViewById(R.id.members);
            last = v.findViewById(R.id.last);
            time = v.findViewById(R.id.time);
        }
    }
}
