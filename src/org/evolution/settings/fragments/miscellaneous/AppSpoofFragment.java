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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.core.InstrumentedFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AppSpoofFragment extends InstrumentedFragment {

    private static final String TAG = "GameSpoofing";

    private static final String CONFIG_DIR = "/data/adb/gameprops";
    private static final String CONFIG_FILE = "gameprops.json";
    private static final String PRESETS_KEY = "game_spoofing_user_presets";

    private final List<AppConfig> mConfigs = new ArrayList<>();
    private final List<DeviceProfile> mProfiles = new ArrayList<>();

    private boolean mEnabled;
    private Switch mEnabledSwitch;
    private TextView mStatusView;
    private TextView mCountView;
    private TextView mEmptyView;
    private LinearLayout mAppContainer;
    private Button mAddGameButton;
    private Button mManagePresetsButton;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().setTitle(R.string.game_spoofing_title);
        loadProfiles();
        loadConfig();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_spoof, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mEnabledSwitch = view.findViewById(R.id.switch_app_spoof_enabled);
        mStatusView = view.findViewById(R.id.tv_app_spoof_status);
        mCountView = view.findViewById(R.id.tv_app_spoof_count);
        mEmptyView = view.findViewById(R.id.tv_app_spoof_empty);
        mAppContainer = view.findViewById(R.id.container_app_spoof_apps);
        mAddGameButton = view.findViewById(R.id.btn_app_spoof_add_app);
        mManagePresetsButton = view.findViewById(R.id.btn_app_spoof_manage_profiles);

        mEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mEnabled = isChecked;
            saveConfig();
            refreshUi();
        });

        mAddGameButton.setOnClickListener(v -> showAddAppDialog());
        mManagePresetsButton.setOnClickListener(v -> showManageProfilesDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfiles();
        loadConfig();
        refreshUi();
    }

    public static boolean isConfigEnabled(@NonNull Context context) {
        File file = new File(CONFIG_DIR, CONFIG_FILE);
        if (!file.exists()) {
            return false;
        }
        try {
            return new JSONObject(readFileStatic(file)).optBoolean("enabled", false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read enabled state", e);
            return false;
        }
    }

    public static int getConfiguredAppCount(@NonNull Context context) {
        File file = new File(CONFIG_DIR, CONFIG_FILE);
        if (!file.exists()) {
            return 0;
        }
        try {
            JSONObject root = new JSONObject(readFileStatic(file));
            JSONObject apps = root.optJSONObject("games");
            return apps != null ? apps.length() : 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to count configs", e);
            return 0;
        }
    }

    private void refreshUi() {
        if (mEnabledSwitch == null) {
            return;
        }

        mEnabledSwitch.setOnCheckedChangeListener(null);
        mEnabledSwitch.setChecked(mEnabled);
        mEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mEnabled = isChecked;
            saveConfig();
            refreshUi();
        });

        mStatusView.setText(mEnabled
                ? getString(R.string.game_spoofing_enabled)
                : getString(R.string.game_spoofing_disabled));
        mCountView.setText(getString(R.string.game_spoofing_configured_count, mConfigs.size()));
        mAddGameButton.setEnabled(mEnabled);
        mManagePresetsButton.setEnabled(mEnabled);

        mAppContainer.removeAllViews();
        if (mConfigs.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            return;
        }
        mEmptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<AppConfig> sorted = new ArrayList<>(mConfigs);
        Collections.sort(sorted, (left, right) -> left.appName.compareToIgnoreCase(right.appName));
        PackageManager packageManager = requireContext().getPackageManager();

        for (AppConfig config : sorted) {
            View itemView = inflater.inflate(R.layout.item_app_spoof_entry, mAppContainer, false);
            ImageView iconView = itemView.findViewById(R.id.iv_app_icon);
            TextView labelView = itemView.findViewById(R.id.tv_app_label);
            TextView packageView = itemView.findViewById(R.id.tv_app_package);
            TextView profileView = itemView.findViewById(R.id.tv_app_profile);

            labelView.setText(config.appName);
            packageView.setText(config.packageName);
            profileView.setText(getString(R.string.game_spoof_preset_summary, config.profileName));

            Drawable icon = null;
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(config.packageName, 0);
                icon = appInfo.loadIcon(packageManager);
            } catch (Exception ignored) {
            }
            iconView.setImageDrawable(icon != null ? icon : requireContext().getDrawable(
                    android.R.drawable.sym_def_app_icon));

            itemView.setEnabled(mEnabled);
            itemView.setAlpha(mEnabled ? 1f : 0.6f);
            itemView.setOnClickListener(mEnabled ? v -> showEditAppDialog(config) : null);
            mAppContainer.addView(itemView);
        }
    }

    private void showAddAppDialog() {
        if (!mEnabled) {
            return;
        }
        new Thread(() -> {
            PackageManager packageManager = requireContext().getPackageManager();
            List<ApplicationInfo> installedApps =
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> availableApps = new ArrayList<>();

            for (ApplicationInfo appInfo : installedApps) {
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (isSystem) {
                    continue;
                }

                boolean alreadyAdded = false;
                for (AppConfig config : mConfigs) {
                    if (config.packageName.equals(appInfo.packageName)) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    availableApps.add(appInfo);
                }
            }

            availableApps.sort((a, b) -> packageManager.getApplicationLabel(a).toString()
                    .compareToIgnoreCase(packageManager.getApplicationLabel(b).toString()));

            String[] labels = new String[availableApps.size()];
            for (int i = 0; i < availableApps.size(); i++) {
                labels[i] = packageManager.getApplicationLabel(availableApps.get(i)).toString();
            }

            requireActivity().runOnUiThread(() -> new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.game_spoof_select_game)
                    .setItems(labels,
                            (dialog, which) -> showPresetPickerDialog(
                                    availableApps.get(which), packageManager, null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }).start();
    }

    private void showPresetPickerDialog(ApplicationInfo appInfo, PackageManager packageManager,
            AppConfig replacing) {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_spoof_no_presets,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        scrollView.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_spoof_select_preset)
                .setView(scrollView)
                .setNeutralButton(R.string.game_spoof_preset_add, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        for (DeviceProfile profile : new ArrayList<>(mProfiles)) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView name = new TextView(requireContext());
            name.setText(profile.name);
            name.setTextSize(16f);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            if (profile.isCustom) {
                TextView delete = new TextView(requireContext());
                delete.setText(R.string.action_delete);
                delete.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
                delete.setPadding(dp(12), 0, 0, 0);
                delete.setOnClickListener(v -> {
                    mProfiles.remove(profile);
                    saveProfiles();
                    dialog.dismiss();
                    showPresetPickerDialog(appInfo, packageManager, replacing);
                });
                row.addView(delete);
            }

            row.setOnClickListener(v -> {
                dialog.dismiss();
                applyPresetToApp(appInfo, packageManager, replacing, profile);
            });
            root.addView(row);
        }

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    showInlinePresetCreationDialog(appInfo, packageManager, replacing);
                }));
        dialog.show();
    }

    private void showEditAppDialog(AppConfig config) {
        if (!mEnabled) {
            return;
        }
        String[] options = {
                getString(R.string.game_spoof_edit_properties),
                getString(R.string.game_spoof_change_preset),
                getString(R.string.game_spoof_remove_game)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(config.appName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showPropertyEditorDialog(config);
                    } else if (which == 1) {
                        PackageManager packageManager = requireContext().getPackageManager();
                        try {
                            ApplicationInfo appInfo =
                                    packageManager.getApplicationInfo(config.packageName, 0);
                            showPresetPickerDialog(appInfo, packageManager, config);
                        } catch (Exception ignored) {
                        }
                    } else {
                        mConfigs.remove(config);
                        saveConfig();
                        refreshUi();
                        Toast.makeText(requireContext(), R.string.game_spoof_game_removed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPropertyEditorDialog(AppConfig config) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        scrollView.addView(root);

        List<EditText[]> propRows = new ArrayList<>();
        LinearLayout propsContainer = new LinearLayout(requireContext());
        propsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(propsContainer);

        if (!config.props.isEmpty()) {
            for (Map.Entry<String, String> entry : config.props.entrySet()) {
                addPropRow(propsContainer, propRows, entry.getKey(), entry.getValue());
            }
        } else {
            addPropRow(propsContainer, propRows, "MODEL", "");
            addPropRow(propsContainer, propRows, "MANUFACTURER", "");
        }

        TextView addPropertyButton = new TextView(requireContext());
        addPropertyButton.setText(R.string.game_spoof_add_property);
        addPropertyButton.setPadding(0, dp(12), 0, dp(8));
        addPropertyButton.setOnClickListener(v -> addPropRow(propsContainer, propRows, "", ""));
        root.addView(addPropertyButton);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.game_spoof_edit_title, config.appName))
                .setView(scrollView)
                .setNeutralButton(R.string.game_spoof_save_as_preset, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Map<String, String> props = collectProps(propRows);
                mConfigs.remove(config);
                mConfigs.add(new AppConfig(config.packageName, config.appName,
                        matchProfileName(props), props));
                saveConfig();
                refreshUi();
                Toast.makeText(requireContext(), R.string.game_spoof_config_saved,
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    showSaveAsPresetDialog(propRows));
        });
        dialog.show();
    }

    private void showInlinePresetCreationDialog(ApplicationInfo appInfo,
            PackageManager packageManager, @Nullable AppConfig replacing) {
        showPresetEditorDialogInternal(null, createdPreset -> {
            loadProfiles();
            applyPresetToApp(appInfo, packageManager, replacing, createdPreset);
        });
    }

    private void showSaveAsPresetDialog(List<EditText[]> propRows) {
        EditText nameView = new EditText(requireContext());
        nameView.setHint(getString(R.string.game_spoof_preset_name));
        nameView.setInputType(InputType.TYPE_CLASS_TEXT);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_spoof_save_as_preset)
                .setView(nameView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameView.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.game_spoof_preset_name_empty,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    mProfiles.add(new DeviceProfile(name, collectProps(propRows), true));
                    saveProfiles();
                    Toast.makeText(requireContext(), R.string.game_spoof_preset_saved,
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showManageProfilesDialog() {
        if (!mEnabled) {
            return;
        }
        String[] options = {
                getString(R.string.game_spoof_preset_add),
                getString(R.string.game_spoof_preset_edit),
                getString(R.string.game_spoof_preset_delete)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_spoof_manage_presets)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showPresetEditorDialog(null);
                    } else if (which == 1) {
                        showPickProfileToEdit();
                    } else {
                        showPickProfileToDelete();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPickProfileToEdit() {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_spoof_no_presets,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_spoof_preset_edit)
                .setItems(getProfileNames(),
                        (dialog, which) -> showPresetEditorDialog(mProfiles.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPickProfileToDelete() {
        if (mProfiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_spoof_no_presets,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.game_spoof_preset_delete)
                .setItems(getProfileNames(), (dialog, which) -> {
                    DeviceProfile profile = mProfiles.get(which);
                    if (!profile.isCustom) {
                        Toast.makeText(requireContext(), R.string.game_spoof_builtin_preset,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.game_spoof_preset_delete)
                            .setMessage(getString(
                                    R.string.game_spoof_preset_delete_confirm, profile.name))
                            .setPositiveButton(R.string.action_delete, (confirmDialog, ignored) -> {
                                mProfiles.remove(profile);
                                saveProfiles();
                                Toast.makeText(requireContext(),
                                        R.string.game_spoof_preset_deleted,
                                        Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPresetEditorDialog(DeviceProfile editing) {
        showPresetEditorDialogInternal(editing, null);
    }

    private void showPresetEditorDialogInternal(@Nullable DeviceProfile editing,
            @Nullable PresetCreatedCallback callback) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        scrollView.addView(root);

        EditText nameView = new EditText(requireContext());
        nameView.setHint(getString(R.string.game_spoof_preset_name));
        nameView.setInputType(InputType.TYPE_CLASS_TEXT);
        if (editing != null) {
            nameView.setText(editing.name);
        }
        root.addView(nameView);

        List<EditText[]> propRows = new ArrayList<>();
        LinearLayout propsContainer = new LinearLayout(requireContext());
        propsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(propsContainer);

        if (editing != null && !editing.props.isEmpty()) {
            for (Map.Entry<String, String> entry : editing.props.entrySet()) {
                addPropRow(propsContainer, propRows, entry.getKey(), entry.getValue());
            }
        } else {
            addPropRow(propsContainer, propRows, "MODEL", "");
            addPropRow(propsContainer, propRows, "MANUFACTURER", "");
        }

        TextView addPropertyButton = new TextView(requireContext());
        addPropertyButton.setText(R.string.game_spoof_add_property);
        addPropertyButton.setPadding(0, dp(12), 0, dp(8));
        addPropertyButton.setOnClickListener(v -> addPropRow(propsContainer, propRows, "", ""));
        root.addView(addPropertyButton);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(editing == null
                        ? R.string.game_spoof_preset_add
                        : R.string.game_spoof_preset_edit)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameView.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.game_spoof_preset_name_empty,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Map<String, String> props = new LinkedHashMap<>();
                    for (EditText[] row : propRows) {
                        String key = row[0].getText().toString().trim();
                        String value = row[1].getText().toString().trim();
                        if (!key.isEmpty()) {
                            props.put(key, value);
                        }
                    }

                    if (editing != null) {
                        mProfiles.remove(editing);
                    }
                    DeviceProfile created = new DeviceProfile(name, props, true);
                    mProfiles.add(created);
                    saveProfiles();
                    Toast.makeText(requireContext(),
                            editing == null
                                    ? getString(R.string.game_spoof_preset_saved_named, name)
                                    : getString(R.string.game_spoof_preset_updated_named, name),
                            Toast.LENGTH_SHORT).show();
                    if (callback != null) {
                        callback.onPresetCreated(created);
                    }
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void addPropRow(LinearLayout container, List<EditText[]> rows,
            String key, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        EditText keyView = new EditText(requireContext());
        keyView.setHint(R.string.game_spoof_property_key_hint);
        keyView.setText(key);
        keyView.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        keyParams.setMarginEnd(dp(8));
        keyView.setLayoutParams(keyParams);

        EditText valueView = new EditText(requireContext());
        valueView.setHint(R.string.game_spoof_property_value_hint);
        valueView.setText(value);
        valueView.setInputType(InputType.TYPE_CLASS_TEXT);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(keyView);
        row.addView(valueView);
        container.addView(row);
        rows.add(new EditText[] {keyView, valueView});
    }

    private void applyPresetToApp(ApplicationInfo appInfo, PackageManager packageManager,
            @Nullable AppConfig replacing, DeviceProfile profile) {
        String appLabel = packageManager.getApplicationLabel(appInfo).toString();

        if (replacing != null) {
            mConfigs.remove(replacing);
        }

        mConfigs.add(new AppConfig(
                appInfo.packageName,
                appLabel,
                profile.name,
                new LinkedHashMap<>(profile.props)));

        saveConfig();
        refreshUi();
        Toast.makeText(requireContext(),
                replacing == null
                        ? getString(R.string.game_spoof_game_added, appLabel)
                        : getString(R.string.game_spoof_game_updated, appLabel),
                Toast.LENGTH_SHORT).show();
    }

    private Map<String, String> collectProps(List<EditText[]> propRows) {
        Map<String, String> props = new LinkedHashMap<>();
        for (EditText[] row : propRows) {
            String key = row[0].getText().toString().trim();
            String value = row[1].getText().toString().trim();
            if (!key.isEmpty()) {
                props.put(key, value);
            }
        }
        return props;
    }

    private int dp(int value) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private String[] getProfileNames() {
        String[] names = new String[mProfiles.size()];
        for (int i = 0; i < mProfiles.size(); i++) {
            names[i] = mProfiles.get(i).name;
        }
        return names;
    }

    private void loadConfig() {
        mConfigs.clear();
        mEnabled = false;

        File file = new File(CONFIG_DIR, CONFIG_FILE);
        if (!file.exists()) {
            return;
        }

        try {
            JSONObject json = new JSONObject(readFile(file));
            mEnabled = json.optBoolean("enabled", false);
            JSONObject apps = json.optJSONObject("games");
            if (apps == null) {
                return;
            }

            PackageManager packageManager = requireContext().getPackageManager();
            Iterator<String> packages = apps.keys();
            while (packages.hasNext()) {
                String packageName = packages.next();
                JSONObject propsJson = apps.getJSONObject(packageName);
                Map<String, String> props = new LinkedHashMap<>();
                Iterator<String> propKeys = propsJson.keys();
                while (propKeys.hasNext()) {
                    String key = propKeys.next();
                    props.put(key, propsJson.getString(key));
                }

                String appName;
                try {
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    appName = packageManager.getApplicationLabel(appInfo).toString();
                } catch (Exception e) {
                    appName = packageName;
                }

                mConfigs.add(new AppConfig(packageName, appName, matchProfileName(props), props));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load game spoof config", e);
        }
    }

    private void saveConfig() {
        try {
            ensureConfigDir();
            JSONObject root = new JSONObject();
            JSONObject apps = new JSONObject();
            root.put("enabled", mEnabled);

            for (AppConfig config : mConfigs) {
                JSONObject props = new JSONObject();
                for (Map.Entry<String, String> entry : config.props.entrySet()) {
                    props.put(entry.getKey(), entry.getValue());
                }
                apps.put(config.packageName, props);
            }

            root.put("games", apps);
            writeFile(new File(CONFIG_DIR, CONFIG_FILE), root.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save game spoof config", e);
        }
    }

    private void loadProfiles() {
        mProfiles.clear();
        mProfiles.addAll(defaultProfiles());

        try {
            String jsonString = Settings.Secure.getString(
                    requireContext().getContentResolver(), PRESETS_KEY);
            if (jsonString == null || jsonString.isEmpty()) {
                return;
            }

            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String profileName = obj.getString("name");
                JSONObject propsJson = obj.getJSONObject("props");
                Map<String, String> props = new LinkedHashMap<>();
                Iterator<String> propKeys = propsJson.keys();
                while (propKeys.hasNext()) {
                    String key = propKeys.next();
                    props.put(key, propsJson.getString(key));
                }
                mProfiles.add(new DeviceProfile(profileName, props, true));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom presets", e);
        }
    }

    private void saveProfiles() {
        try {
            JSONArray root = new JSONArray();
            for (DeviceProfile profile : mProfiles) {
                if (!profile.isCustom) {
                    continue;
                }
                JSONObject preset = new JSONObject();
                JSONObject props = new JSONObject();
                for (Map.Entry<String, String> entry : profile.props.entrySet()) {
                    props.put(entry.getKey(), entry.getValue());
                }
                preset.put("name", profile.name);
                preset.put("props", props);
                root.put(preset);
            }
            Settings.Secure.putString(requireContext().getContentResolver(),
                    PRESETS_KEY, root.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save custom presets", e);
        }
    }

    private String matchProfileName(Map<String, String> props) {
        for (DeviceProfile profile : mProfiles) {
            if (profile.props.equals(props)) {
                return profile.name;
            }
        }

        String manufacturer = props.get("MANUFACTURER");
        String model = props.get("MODEL");
        if (manufacturer != null && model != null) {
            return manufacturer + " " + model;
        }
        if (model != null) {
            return model;
        }
        return getString(R.string.game_spoof_custom);
    }

    private void ensureConfigDir() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private String readFile(File file) throws Exception {
        return readFileStatic(file);
    }

    private void writeFile(File file, String content) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        file.setReadable(true, false);
    }

    private static String readFileStatic(File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
        }
        return builder.toString();
    }

    private static List<DeviceProfile> defaultProfiles() {
        List<DeviceProfile> profiles = new ArrayList<>();
        profiles.add(new DeviceProfile("ROG Phone 8 Pro",
                mapOf("MODEL", "ASUS_AI2401_A", "MANUFACTURER", "asus")));
        profiles.add(new DeviceProfile("Galaxy S24 Ultra",
                mapOf("MODEL", "SM-S928B", "MANUFACTURER", "samsung")));
        profiles.add(new DeviceProfile("Xiaomi 13 Pro",
                mapOf("MODEL", "2210132C", "MANUFACTURER", "Xiaomi")));
        profiles.add(new DeviceProfile("OnePlus 9 Pro",
                mapOf("MODEL", "LE2101", "MANUFACTURER", "OnePlus")));
        profiles.add(new DeviceProfile("Black Shark 4",
                mapOf("MODEL", "2SM-X706B", "MANUFACTURER", "blackshark")));
        profiles.add(new DeviceProfile("Lenovo Y700",
                mapOf("MODEL", "Lenovo TB-9707F", "MANUFACTURER", "Lenovo")));
        return profiles;
    }

    private static Map<String, String> mapOf(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    private static final class AppConfig {
        final String packageName;
        final String appName;
        final String profileName;
        final Map<String, String> props;

        AppConfig(String packageName, String appName, String profileName,
                Map<String, String> props) {
            this.packageName = packageName;
            this.appName = appName;
            this.profileName = profileName;
            this.props = props;
        }
    }

    private static final class DeviceProfile {
        final String name;
        final Map<String, String> props;
        final boolean isCustom;

        DeviceProfile(String name, Map<String, String> props) {
            this(name, props, false);
        }

        DeviceProfile(String name, Map<String, String> props, boolean isCustom) {
            this.name = name;
            this.props = props;
            this.isCustom = isCustom;
        }
    }

    private interface PresetCreatedCallback {
        void onPresetCreated(DeviceProfile preset);
    }
}
