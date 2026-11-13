package com.chatscopex.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.StatusItem;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.TimeFormat;
import com.chatscopex.app.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status tab: my own status on top, then recent updates from people I chat with or share a group with.
 * The server decides who may see what and hides statuses older than 24 hours. Refreshes on resume.
 */
public class StatusFragment extends Fragment {

    /** One person's active statuses, newest first. */
    static class OwnerGroup {
        final String owner;
        final List<StatusItem> items;
        OwnerGroup(String owner, List<StatusItem> items) { this.owner = owner; this.items = items; }
    }

    private String myUid = "";
    private ImageView myAvatar;
    private View myRing;
    private TextView mySub;
    private StatusAdapter adapter;
    private TextView empty;
    private ProgressBar progress;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_status, container, false);
        myUid = Session.myId();

        View myRow = v.findViewById(R.id.my_row);
        myAvatar = v.findViewById(R.id.my_avatar);
        myRing = v.findViewById(R.id.my_ring);
        Avatars.ring(myRing);
        mySub = v.findViewById(R.id.my_sub);
        empty = v.findViewById(R.id.empty);
        progress = v.findViewById(R.id.progress);

        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new StatusAdapter(this::openViewer);
        list.setAdapter(adapter);

        myRow.setOnClickListener(x -> {
            if (myRing.getVisibility() == View.VISIBLE) openViewer(myUid);
            else startActivity(new Intent(requireContext(), StatusComposerActivity.class));
        });
        v.findViewById(R.id.fab).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), StatusComposerActivity.class)));
        return v;
    }

    private void openViewer(String owner) {
        startActivity(new Intent(requireContext(), StatusViewActivity.class)
                .putExtra(StatusViewActivity.EXTRA_OWNER, owner));
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        ProfileCache.load(myUid, p -> { if (isAdded()) Avatars.setUser(myAvatar, p.avatar, myUid); });
        progress.setVisibility(View.VISIBLE);
        Api.call("GET", "/api/statuses", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                render(json.optJSONArray("statuses"));
            }

            @Override
            public void fail(ApiError e) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                Ui.snack(requireActivity(), ErrorMapper.res(e));
            }
        });
    }

    private void render(JSONArray arr) {
        long now = System.currentTimeMillis();
        Map<String, List<StatusItem>> byOwner = new LinkedHashMap<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                StatusItem s = StatusItem.from(o);
                if (!s.isActive(now)) continue;
                // The response carries the owner's name and picture: cache them for the list rows.
                ProfileCache.put(new UserProfile(s.ownerId, o.optString("displayName"), o.optString("username"),
                        o.isNull("avatar") ? null : o.optString("avatar", null)));
                List<StatusItem> l = byOwner.get(s.ownerId);
                if (l == null) byOwner.put(s.ownerId, l = new ArrayList<>());
                l.add(s);
            }
        }

        List<StatusItem> mine = byOwner.remove(myUid);
        if (mine != null && !mine.isEmpty()) {
            myRing.setVisibility(View.VISIBLE);
            mySub.setText(TimeFormat.format(requireContext(), mine.get(0).createdMillis));
        } else {
            myRing.setVisibility(View.GONE);
            mySub.setText(R.string.status_add_hint);
        }

        List<OwnerGroup> rows = new ArrayList<>();
        for (Map.Entry<String, List<StatusItem>> e : byOwner.entrySet()) {
            rows.add(new OwnerGroup(e.getKey(), e.getValue()));
        }
        adapter.submit(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
