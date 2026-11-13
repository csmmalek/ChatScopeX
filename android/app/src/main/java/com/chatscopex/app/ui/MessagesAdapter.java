package com.chatscopex.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.Message;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.util.AudioPlayer;
import com.chatscopex.app.util.MediaLoader;
import com.chatscopex.app.util.Palette;
import com.chatscopex.app.util.Previews;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.TimeFormat;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chat bubbles. Colors come from the active theme; the bubble "tail" follows the sender's side in RTL and LTR. */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.VH> {
    public interface RetryListener { void onRetry(Message m); }

    private static final int READ_TICK = 0xFF9EE7FF;

    private final Context ctx;
    private final String myUid;
    private final boolean group;
    private final RetryListener retry;
    private final int colorOut, colorIn, textOut, textIn, errorColor;
    private final float radius, tail;
    private final GradientDrawable[] bubbles = new GradientDrawable[4];   // [mine*2 + rtl]
    private final Set<String> loadingNames = new HashSet<>();
    private List<Message> items = new ArrayList<>();
    private long otherReadMillis = 0;

    public MessagesAdapter(Context c, String myUid, boolean group, RetryListener retry) {
        this.ctx = c;
        this.myUid = myUid;
        this.group = group;
        this.retry = retry;
        colorOut = MaterialColors.getColor(c, R.attr.csBubbleOut, 0xFF0F766E);
        colorIn = MaterialColors.getColor(c, R.attr.csBubbleIn, 0xFFE8EEF0);
        textOut = MaterialColors.getColor(c, R.attr.csBubbleOutText, 0xFFFFFFFF);
        textIn = MaterialColors.getColor(c, R.attr.csBubbleInText, 0xFF14212A);
        errorColor = c.getColor(R.color.error_red);
        radius = dp(c, 18);
        tail = dp(c, 4);
    }

    public void submit(List<Message> list, long otherReadMillis) {
        this.items = list;
        this.otherReadMillis = otherReadMillis;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = items.get(position);
        boolean mine = m.senderId.equals(myUid);
        boolean rtl = h.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        h.root.setGravity(mine ? Gravity.END : Gravity.START);
        h.bubble.setBackground(bubble(mine, rtl));
        int textColor = mine ? textOut : textIn;
        bindContent(h, m, textColor);

        if (group && !mine) {
            h.sender.setVisibility(View.VISIBLE);
            h.sender.setText(nameFor(m.senderId));
            h.sender.setTextColor(Palette.nameColor(ctx, m.senderId));
        } else {
            h.sender.setVisibility(View.GONE);
        }

        if (m.failed) {
            h.meta.setText(R.string.message_failed);
            h.meta.setTextColor(errorColor);
            h.bubble.setOnClickListener(v -> retry.onRetry(m));
        } else {
            h.bubble.setOnClickListener(null);
            h.bubble.setClickable(false);
            String time = TimeFormat.format(ctx, m.timeMillis);
            if (mine) {
                // Direct chats: sent / read. Group chats have no receipts, so only sent / pending.
                boolean read = !group && !m.pending && m.timeMillis <= otherReadMillis;
                String tick = m.pending ? "\u23F3" : (read ? "\u2713\u2713" : "\u2713");
                h.meta.setText(time + "  " + tick);
                h.meta.setTextColor(read ? READ_TICK : textColor);
            } else {
                h.meta.setText(time);
                h.meta.setTextColor(textColor);
            }
        }
        h.meta.setAlpha(0.8f);
    }

    /** Text, image or voice note. A media message without a mediaId is still uploading: show a label. */
    private void bindContent(VH h, final Message m, int textColor) {
        boolean ready = m.mediaId != null;
        boolean isImage = Message.IMAGE.equals(m.type);
        boolean isAudio = Message.AUDIO.equals(m.type);
        h.image.setVisibility(isImage && ready ? View.VISIBLE : View.GONE);
        h.audio.setVisibility(isAudio && ready ? View.VISIBLE : View.GONE);
        if (isImage && ready) MediaLoader.load(h.image, m.mediaId);
        if (isAudio && ready) {
            boolean playing = AudioPlayer.isPlaying(m.mediaId);
            h.audio.setText((playing ? "\u23F8  " : "\u25B6  ") + ctx.getString(R.string.msg_audio));
            h.audio.setTextColor(textColor);
            h.audio.setOnClickListener(v -> AudioPlayer.toggle(ctx, m.mediaId, () -> notifyDataSetChanged()));
        }
        String shown = (!ready && !Message.TEXT.equals(m.type)) ? Previews.text(ctx, m.type, m.text) : m.text;
        h.text.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        h.text.setText(shown);
        h.text.setTextColor(textColor);
    }

    private String nameFor(String uid) {
        UserProfile p = ProfileCache.peek(uid);
        if (p != null) return p.displayName;
        if (loadingNames.add(uid)) ProfileCache.load(uid, loaded -> notifyDataSetChanged());
        return ctx.getString(R.string.unknown_user);
    }

    /** Cached per side and direction, so bubbles are not rebuilt on every bind. */
    private GradientDrawable bubble(boolean mine, boolean rtl) {
        int idx = (mine ? 2 : 0) + (rtl ? 1 : 0);
        if (bubbles[idx] == null) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(mine ? colorOut : colorIn);
            g.setCornerRadius(radius);
            // The corner nearest the sender's edge is squared off, so the bubble looks like it has a tail.
            boolean tailRight = (mine && !rtl) || (!mine && rtl);
            float br = tailRight ? tail : radius;
            float bl = tailRight ? radius : tail;
            g.setCornerRadii(new float[]{radius, radius, radius, radius, br, br, bl, bl});
            bubbles[idx] = g;
        }
        return bubbles[idx];
    }

    @Override
    public int getItemCount() { return items.size(); }

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout root, bubble;
        final TextView sender, text, meta, audio;
        final ImageView image;

        VH(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.root);
            bubble = v.findViewById(R.id.bubble);
            sender = v.findViewById(R.id.sender);
            text = v.findViewById(R.id.text);
            meta = v.findViewById(R.id.meta);
            audio = v.findViewById(R.id.audio);
            image = v.findViewById(R.id.image);
        }
    }
}
