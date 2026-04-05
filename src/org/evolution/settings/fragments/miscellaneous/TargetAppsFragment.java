/*
 * Copyright (C) 2026 AxionOS
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.miscellaneous;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetAppsFragment extends Fragment {

    public enum TargetMode {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        public final String symbol;

        TargetMode(String symbol) {
            this.symbol = symbol;
        }
    }

    private KeyboxManager mKeyboxManager;
    private AppAdapter mAdapter;
    private List<AppEntry> mAllApps = new ArrayList<>();
    private List<AppEntry> mFilteredApps = new ArrayList<>();
    private boolean mShowSystem;
    private String mSearchQuery = "";

    private TextView mSelectedCount;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mKeyboxManager = new KeyboxManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_target_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recyclerView = view.findViewById(R.id.rv_apps);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new AppAdapter();
        recyclerView.setAdapter(mAdapter);

        mSelectedCount = view.findViewById(R.id.tv_selected_count);

        EditText searchApps = view.findViewById(R.id.et_search_apps);
        searchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSearchQuery = s != null ? s.toString().trim() : "";
                filterApps();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        CheckBox showSystemApps = view.findViewById(R.id.cb_show_system_apps);
        showSystemApps.setChecked(mShowSystem);
        showSystemApps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mShowSystem = isChecked;
            filterApps();
        });

        loadApps();
    }

    @Override
    public void onPause() {
        super.onPause();
        saveTargetFile();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager packageManager = requireContext().getPackageManager();
            Map<String, TargetMode> currentTargets = loadCurrentTargets();
            List<ApplicationInfo> installedApps =
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppEntry> entries = new ArrayList<>();

            for (ApplicationInfo applicationInfo : installedApps) {
                AppEntry entry = new AppEntry();
                entry.packageName = applicationInfo.packageName;
                entry.label = packageManager.getApplicationLabel(applicationInfo).toString();
                entry.icon = applicationInfo.loadIcon(packageManager);
                entry.isSystem = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                entry.inTarget = currentTargets.containsKey(applicationInfo.packageName);
                entry.mode = currentTargets.getOrDefault(applicationInfo.packageName, TargetMode.AUTO);
                entries.add(entry);
            }

            mAllApps = entries;
            sortApps();
            requireActivity().runOnUiThread(this::filterApps);
        }).start();
    }

    private void sortApps() {
        mAllApps.sort((a, b) -> {
            if (a.inTarget != b.inTarget) {
                return a.inTarget ? -1 : 1;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
    }

    private void filterApps() {
        List<AppEntry> filtered = new ArrayList<>();
        String query = mSearchQuery.toLowerCase();
        for (AppEntry entry : mAllApps) {
            if (!mShowSystem && entry.isSystem && !entry.inTarget) {
                continue;
            }
            if (!query.isEmpty()) {
                String label = entry.label.toLowerCase();
                String pkg = entry.packageName.toLowerCase();
                if (!label.contains(query) && !pkg.contains(query)) {
                    continue;
                }
            }
            filtered.add(entry);
        }
        mFilteredApps = filtered;
        if (mSelectedCount != null) {
            int selectedCount = 0;
            for (AppEntry entry : mAllApps) {
                if (entry.inTarget) {
                    selectedCount++;
                }
            }
            mSelectedCount.setText(getString(R.string.target_selected_count, selectedCount));
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private Map<String, TargetMode> loadCurrentTargets() {
        Map<String, TargetMode> targets = new HashMap<>();
        for (String line : mKeyboxManager.readTargetLines()) {
            if (line.endsWith("?")) {
                targets.put(line.substring(0, line.length() - 1), TargetMode.LEAF_HACK);
            } else if (line.endsWith("!")) {
                targets.put(line.substring(0, line.length() - 1), TargetMode.CERT_GEN);
            } else {
                targets.put(line, TargetMode.AUTO);
            }
        }
        return targets;
    }

    private void saveTargetFile() {
        List<String> lines = new ArrayList<>();
        for (AppEntry entry : mAllApps) {
            if (entry.inTarget) {
                lines.add(entry.packageName + entry.mode.symbol);
            }
        }
        mKeyboxManager.saveTargetLines(lines);
    }

    private static final class AppEntry {
        String packageName;
        String label;
        Drawable icon;
        boolean isSystem;
        boolean inTarget;
        TargetMode mode = TargetMode.AUTO;
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_target_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppEntry entry = mFilteredApps.get(position);

            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName);

            holder.enabled.setOnCheckedChangeListener(null);
            holder.modeGroup.setOnCheckedChangeListener(null);

            holder.enabled.setChecked(entry.inTarget);
            holder.modeGroup.setVisibility(entry.inTarget ? View.VISIBLE : View.GONE);
            if (entry.mode == TargetMode.LEAF_HACK) {
                holder.modeGroup.check(R.id.rb_leaf_hack);
            } else if (entry.mode == TargetMode.CERT_GEN) {
                holder.modeGroup.check(R.id.rb_cert_gen);
            } else {
                holder.modeGroup.check(R.id.rb_auto);
            }

            holder.enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                entry.inTarget = isChecked;
                if (!isChecked) {
                    entry.mode = TargetMode.AUTO;
                }
                holder.modeGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                sortApps();
                filterApps();
                saveTargetFile();
            });

            holder.itemView.setOnClickListener(v -> holder.enabled.toggle());

            holder.modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.rb_leaf_hack) {
                    entry.mode = TargetMode.LEAF_HACK;
                } else if (checkedId == R.id.rb_cert_gen) {
                    entry.mode = TargetMode.CERT_GEN;
                } else {
                    entry.mode = TargetMode.AUTO;
                }
                saveTargetFile();
            });
        }

        @Override
        public int getItemCount() {
            return mFilteredApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView packageName;
            final CheckBox enabled;
            final RadioGroup modeGroup;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.iv_app_icon);
                label = itemView.findViewById(R.id.tv_app_label);
                packageName = itemView.findViewById(R.id.tv_app_package);
                enabled = itemView.findViewById(R.id.cb_app_enabled);
                modeGroup = itemView.findViewById(R.id.rg_mode);
            }
        }
    }
}
