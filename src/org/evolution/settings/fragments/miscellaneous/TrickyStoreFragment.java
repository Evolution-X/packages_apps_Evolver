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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SubSettings;
import com.android.settings.core.InstrumentedFragment;

public class TrickyStoreFragment extends InstrumentedFragment {

    private static final String TAG = "TrickyStoreFragment";

    private KeyboxManager mKeyboxManager;

    private TextView mKeyboxStatus;
    private TextView mKeyboxDetail;
    private TextView mTargetStatus;
    private TextView mTargetDetail;
    private Button mDeleteKeyboxButton;

    private final ActivityResultLauncher<Intent> mKeyboxFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                importKeyboxFile(uri);
                            }
                        }
                    });

    private final ActivityResultLauncher<Intent> mTargetFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                importTargetFile(uri);
                            }
                        }
                    });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().setTitle(R.string.trickystore_screen_title);
        mKeyboxManager = new KeyboxManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trickystore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mKeyboxStatus = view.findViewById(R.id.tv_keybox_status);
        mKeyboxDetail = view.findViewById(R.id.tv_keybox_detail);
        mTargetStatus = view.findViewById(R.id.tv_target_status);
        mTargetDetail = view.findViewById(R.id.tv_target_detail);
        mDeleteKeyboxButton = view.findViewById(R.id.btn_keybox_delete);

        view.findViewById(R.id.btn_keybox_import).setOnClickListener(v -> openKeyboxFilePicker());
        mDeleteKeyboxButton.setOnClickListener(v -> confirmDeleteKeybox());
        view.findViewById(R.id.btn_target_manage).setOnClickListener(v ->
                openFragment(TargetAppsFragment.class, getString(R.string.target_screen_title)));
        view.findViewById(R.id.btn_target_import).setOnClickListener(v -> openTargetFilePicker());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        boolean keyboxExists = mKeyboxManager.keyboxExists();
        int targetCount = mKeyboxManager.getTargetAppCount();

        mKeyboxStatus.setText(keyboxExists
                ? getString(R.string.keybox_installed)
                : getString(R.string.keybox_not_found));
        mKeyboxDetail.setText(keyboxExists
                ? getString(R.string.spoof_dashboard_keybox_present_detail)
                : getString(R.string.keybox_delete_summary));
        mDeleteKeyboxButton.setEnabled(keyboxExists);

        mTargetStatus.setText(targetCount > 0
                ? getString(R.string.target_apps_count, targetCount)
                : getString(R.string.target_no_apps));
        mTargetDetail.setText(targetCount > 0
                ? getString(R.string.spoof_dashboard_target_present_detail)
                : getString(R.string.target_import_summary));
    }

    private void openKeyboxFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/xml", "application/xml"});
        mKeyboxFileLauncher.launch(intent);
    }

    private void importKeyboxFile(@NonNull Uri uri) {
        try {
            mKeyboxManager.importKeybox(uri);
            refreshUi();
            Toast.makeText(requireContext(), R.string.keybox_import_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "importKeyboxFile error", e);
            Toast.makeText(requireContext(),
                    getString(R.string.keybox_import_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteKeybox() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.keybox_delete_title)
                .setMessage(R.string.keybox_delete_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    mKeyboxManager.deleteKeybox();
                    refreshUi();
                    Toast.makeText(requireContext(), R.string.keybox_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openTargetFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        mTargetFileLauncher.launch(intent);
    }

    private void importTargetFile(@NonNull Uri uri) {
        try {
            mKeyboxManager.importTargetFile(uri);
            refreshUi();
            Toast.makeText(requireContext(), R.string.target_import_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "importTargetFile error", e);
            Toast.makeText(requireContext(),
                    getString(R.string.target_import_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openFragment(@NonNull Class<? extends androidx.fragment.app.Fragment> fragmentClass,
            @NonNull String title) {
        Intent intent = new Intent(requireActivity(), SubSettings.class);
        intent.putExtra(":settings:show_fragment", fragmentClass.getName());
        intent.putExtra(":settings:show_fragment_title", title);
        startActivity(intent);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }
}
