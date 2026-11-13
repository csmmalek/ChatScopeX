package com.chatscopex.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.CallRecord;
import com.chatscopex.app.util.Profiles;
import com.chatscopex.app.util.TimeFormat;

import java.util.ArrayList;
import java.util.List;

/** Call history rows with one-tap voice and video buttons. */
public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.VH> {
    public interface Listener { void onCall(String otherUid, boolean video); }

    private final String myUid;
    private final Listener listener;
    private List<CallRecord> items = new ArrayList<>();

    public CallsAdapter(String myUid, Listener listener) {
        this.myUid = myUid;
        this.listener = listener;
    }

    public void submit(List<CallRecord> list) {
        items = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_call, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        CallRecord r = items.get(position);
        String other = r.otherUid(myUid);
        Profiles.bind(h.avatar, h.name, other, () -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < items.size()) notifyItemChanged(pos);
        });

        h.detail.setText(describe(c, r));
        h.time.setText(TimeFormat.format(c, r.createdMillis));
        h.voice.setOnClickListener(v -> listener.onCall(other, false));
        h.video.setOnClickListener(v -> listener.onCall(other, true));
    }

    /** Direction and outcome, e.g. "Incoming · answered", "Missed", "Outgoing · no answer". */
    private String describe(Context c, CallRecord r) {
        String kind = r.isVideo() ? c.getString(R.string.call_kind_video) : c.getString(R.string.call_kind_voice);
        String outcome;
        if (r.isOutgoing(myUid)) {
            outcome = r.answered ? c.getString(R.string.call_out)
                    : "declined".equals(r.status) ? c.getString(R.string.call_was_declined)
                    : c.getString(R.string.call_no_answer);
        } else {
            outcome = r.answered ? c.getString(R.string.call_in)
                    : "declined".equals(r.status) ? c.getString(R.string.call_declined_by_me)
                    : c.getString(R.string.call_missed);
        }
        return kind + " · " + outcome;
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name, detail, time;
        final ImageButton voice, video;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            detail = v.findViewById(R.id.detail);
            time = v.findViewById(R.id.time);
            voice = v.findViewById(R.id.btn_voice);
            video = v.findViewById(R.id.btn_video);
        }
    }
}
