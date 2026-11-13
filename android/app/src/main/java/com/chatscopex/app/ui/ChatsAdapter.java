package com.chatscopex.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.ChatSummary;
import com.chatscopex.app.util.Previews;
import com.chatscopex.app.util.Profiles;
import com.chatscopex.app.util.TimeFormat;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.VH> {
    public interface Listener { void onOpen(ChatSummary chat, String otherName); }

    private final String myUid;
    private final Listener listener;
    private List<ChatSummary> items = new ArrayList<>();

    public ChatsAdapter(String myUid, Listener listener) {
        this.myUid = myUid;
        this.listener = listener;
    }

    public void submit(List<ChatSummary> list) {
        items = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        ChatSummary chat = items.get(position);

        Profiles.bind(h.avatar, h.name, chat.otherUid, () -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < items.size()
                    && items.get(pos).otherUid.equals(chat.otherUid)) notifyItemChanged(pos);
        });
        String name = h.name.getText().toString();

        String last = Previews.text(c, chat.lastType, chat.lastText);
        if (chat.lastSenderId.equals(myUid)) last = c.getString(R.string.you_prefix, last);
        h.last.setText(last);
        h.time.setText(TimeFormat.format(c, chat.lastTimeMillis));

        h.unread.setVisibility(chat.unread ? View.VISIBLE : View.GONE);
        if (chat.unread) {
            int color = MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimary, 0xFF0F766E);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            h.unread.setBackground(dot);
        }
        int style = chat.unread ? Typeface.BOLD : Typeface.NORMAL;
        h.name.setTypeface(null, style);
        h.last.setTypeface(null, style);

        h.itemView.setOnClickListener(v -> listener.onOpen(chat, name));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name, last, time;
        final View unread;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            last = v.findViewById(R.id.last);
            time = v.findViewById(R.id.time);
            unread = v.findViewById(R.id.unread);
        }
    }
}
