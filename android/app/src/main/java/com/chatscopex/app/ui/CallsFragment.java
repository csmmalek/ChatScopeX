package com.chatscopex.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.CallRecord;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.ErrorMapper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Call history (placed and received) with voice/video shortcuts. Refreshes on resume. */
public class CallsFragment extends Fragment {
    private CallsAdapter adapter;
    private TextView empty, error;
    private ProgressBar progress;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) return;
                final String[] uids = r.getData().getStringArrayExtra(UserSearchActivity.RESULT_UIDS);
                String[] names = r.getData().getStringArrayExtra(UserSearchActivity.RESULT_NAMES);
                if (uids == null || uids.length == 0) return;
                final String name = names != null && names.length > 0 ? names[0] : null;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(name != null ? name : getString(R.string.unknown_user))
                        .setItems(new String[]{getString(R.string.call_voice), getString(R.string.call_video)},
                                (d, which) -> startCall(uids[0], name, which == 1))
                        .show();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, container, false);
        progress = v.findViewById(R.id.progress);
        empty = v.findViewById(R.id.empty);
        error = v.findViewById(R.id.error);
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new CallsAdapter(Session.myId(), (uid, video) -> startCall(uid, null, video));
        list.setAdapter(adapter);
        v.findViewById(R.id.fab).setOnClickListener(x -> pickLauncher.launch(
                new Intent(requireContext(), UserSearchActivity.class)
                        .putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_PICK_ONE)));
        return v;
    }

    private void startCall(String uid, String name, boolean video) {
        startActivity(CallActivity.outgoing(requireContext(), uid, name, video));
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        Api.call("GET", "/api/calls", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (!isAdded()) return;
                List<CallRecord> out = new ArrayList<>();
                JSONArray arr = json.optJSONArray("calls");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) out.add(CallRecord.from(o));
                    }
                }
                progress.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
                adapter.submit(out);
                empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void fail(ApiError e) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                error.setText(ErrorMapper.res(e));
                error.setVisibility(View.VISIBLE);
            }
        });
    }
}
