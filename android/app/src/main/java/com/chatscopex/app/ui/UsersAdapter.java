package com.chatscopex.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.util.Avatars;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** User rows. When selectable, a check mark shows whether the user is in the current selection. */
public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
    public interface Listener { void onPick(UserProfile user); }

    private final Listener listener;
    private final boolean selectable;
    private Set<String> selected = null;
    private List<UserProfile> items = new ArrayList<>();

    public UsersAdapter(Listener listener, boolean selectable) {
        this.listener = listener;
        this.selectable = selectable;
    }

    public void submit(List<UserProfile> list) {
        items = list;
        notifyDataSetChanged();
    }

    public void setSelection(Set<String> uids) {
        selected = uids;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        UserProfile u = items.get(position);
        h.name.setText(u.displayName);
        h.username.setText(u.username.isEmpty() ? "" : "@" + u.username);
        Avatars.setUser(h.avatar, u.avatar, u.uid);
        boolean isSel = selectable && selected != null && selected.contains(u.uid);
        h.check.setVisibility(selectable ? View.VISIBLE : View.GONE);
        h.check.setImageResource(isSel ? R.drawable.ic_check_on : R.drawable.ic_check_off);
        h.itemView.setOnClickListener(v -> listener.onPick(u));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar, check;
        final TextView name, username;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            username = v.findViewById(R.id.username);
            check = v.findViewById(R.id.check);
        }
    }
}
