package com.chatscopex.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.Palette;
import com.chatscopex.app.util.Prefs;
import com.chatscopex.app.util.ProfileCache;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.json.JSONObject;

/** "Me" tab: profile card, appearance (accent theme and light/dark), edit profile, sign out. */
public class SettingsFragment extends Fragment {
    private ImageView avatar;
    private TextView name, username;
    private LinearLayout accents;
    private MaterialButtonToggleGroup nightToggle;
    private boolean suppressToggle = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        avatar = v.findViewById(R.id.avatar);
        name = v.findViewById(R.id.name);
        username = v.findViewById(R.id.username);
        accents = v.findViewById(R.id.accents);
        nightToggle = v.findViewById(R.id.night_toggle);
        Button editBtn = v.findViewById(R.id.edit_profile);
        Button signOut = v.findViewById(R.id.sign_out);

        editBtn.setOnClickListener(x -> startActivity(
                new Intent(requireContext(), ProfileSetupActivity.class)
                        .putExtra(ProfileSetupActivity.EXTRA_EDIT, true)));
        signOut.setOnClickListener(x -> signOut());
        avatar.setOnClickListener(x -> editBtn.performClick());

        buildAccentSwatches();
        setupNightToggle();
        return v;
    }

    private void buildAccentSwatches() {
        accents.removeAllViews();
        int selected = Prefs.accent(requireContext());
        int size = dp(44), margin = dp(8);
        for (int i = 0; i < Palette.ACCENT_COUNT; i++) {
            final int idx = i;
            View swatch = new View(requireContext());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ContextCompat.getColor(requireContext(), Palette.ACCENT_SWATCHES[i]));
            if (i == selected) g.setStroke(dp(3), ContextCompat.getColor(requireContext(), R.color.swatch_ring));
            swatch.setBackground(g);
            swatch.setContentDescription(getString(Palette.ACCENT_NAMES[i]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(x -> {
                if (idx == Prefs.accent(requireContext())) return;
                Prefs.setAccent(requireContext(), idx);
                requireActivity().recreate();   // re-applies the new theme everywhere
            });
            accents.addView(swatch);
        }
    }

    private void setupNightToggle() {
        int mode = Prefs.night(requireContext());
        int checkedId = mode == Prefs.NIGHT_LIGHT ? R.id.night_light
                : mode == Prefs.NIGHT_DARK ? R.id.night_dark : R.id.night_system;
        suppressToggle = true;
        nightToggle.check(checkedId);
        suppressToggle = false;
        nightToggle.addOnButtonCheckedListener((group, id, isChecked) -> {
            if (!isChecked || suppressToggle) return;
            int m = id == R.id.night_light ? Prefs.NIGHT_LIGHT
                    : id == R.id.night_dark ? Prefs.NIGHT_DARK : Prefs.NIGHT_SYSTEM;
            Prefs.setNight(requireContext(), m);   // applies the mode and recreates the activity
        });
    }

    @Override
    public void onResume() {
        super.onResume(); // also refreshes after returning from "edit profile"
        UserProfile cached = ProfileCache.peek(Session.myId());
        if (cached != null) show(cached);
        Api.call("GET", "/api/me", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject u = json.optJSONObject("user");
                if (!isAdded() || u == null) return;
                UserProfile p = UserProfile.from(u);
                ProfileCache.put(p);
                show(p);
            }

            @Override
            public void fail(ApiError e) { }   // keep showing the cached profile
        });
    }

    private void show(UserProfile p) {
        Avatars.setUser(avatar, p.avatar, p.uid);
        name.setText(p.displayName);
        username.setText("@" + p.username);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Ends the session on the server (best effort), then wipes it from the phone and returns to login. */
    private void signOut() {
        final android.content.Context app = requireContext().getApplicationContext();
        Api.call("POST", "/api/logout", null, new Api.Cb() {
            @Override public void ok(JSONObject json) { leave(app); }
            @Override public void fail(ApiError e) { leave(app); }   // the token is wiped locally either way
        });
    }

    private void leave(android.content.Context app) {
        Session.clear();
        ProfileCache.clear();
        Intent i = new Intent(app, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
    }
}
