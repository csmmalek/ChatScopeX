package com.chatscopex.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.GroupSummary;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.Poller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Group list. Refreshes every 6 seconds while the tab is visible. */
public class GroupsFragment extends Fragment {
    private static final long POLL_MS = 6000;

    private GroupsAdapter adapter;
    private Poller poller;
    private ProgressBar progress;
    private TextView empty, error;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_groups, container, false);
        progress = v.findViewById(R.id.progress);
        empty = v.findViewById(R.id.empty);
        error = v.findViewById(R.id.error);
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new GroupsAdapter(Session.myId(), g -> startActivity(
                new Intent(requireContext(), ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_GROUP_ID, g.id)
                        .putExtra(ChatActivity.EXTRA_GROUP_NAME, g.name)));
        list.setAdapter(adapter);
        v.findViewById(R.id.fab).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), GroupCreateActivity.class)));
        poller = new Poller(this::load, POLL_MS);
        return v;
    }

    private void load() {
        Api.call("GET", "/api/groups", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
                List<GroupSummary> out = new ArrayList<>();
                JSONArray arr = json.optJSONArray("groups");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) out.add(GroupSummary.from(o));
                    }
                }
                adapter.submit(out);
                empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void fail(ApiError e) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                if (adapter.getItemCount() == 0) {
                    error.setText(ErrorMapper.res(e));
                    error.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        poller.start();
    }

    @Override
    public void onStop() {
        super.onStop();
        poller.stop();
    }
}
