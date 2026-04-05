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
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.core.InstrumentedFragment;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class PifFragment extends InstrumentedFragment {

    private static final String TAG = "PifFragment";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PifManager mPifManager;
    private PifRepository mPifRepository;

    private TextView mActiveConfig;
    private TextView mModel;
    private TextView mFingerprint;
    private TextView mSecurityPatch;
    private Button mFetchBeta;
    private Button mFetchEvolution;
    private Button mImportButton;
    private Button mShowPropsButton;
    private LinearLayout mConfigContainer;
    private String mImportTargetFileName;

    private final ActivityResultLauncher<android.content.Intent> mPifFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                importPifFile(uri);
                            }
                        } else {
                            mImportTargetFileName = null;
                        }
                    });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().setTitle(R.string.pif_category_title);
        mPifManager = new PifManager(requireContext());
        mPifRepository = new PifRepository();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_pif, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mActiveConfig = view.findViewById(R.id.tv_pif_active_config);
        mModel = view.findViewById(R.id.tv_pif_model);
        mFingerprint = view.findViewById(R.id.tv_pif_fingerprint);
        mSecurityPatch = view.findViewById(R.id.tv_pif_security_patch);
        mFetchBeta = view.findViewById(R.id.btn_pif_fetch_beta);
        mFetchEvolution = view.findViewById(R.id.btn_pif_fetch_evolution);
        mImportButton = view.findViewById(R.id.btn_pif_import);
        mShowPropsButton = view.findViewById(R.id.btn_pif_show_props);
        mConfigContainer = view.findViewById(R.id.container_pif_configs);

        mFetchBeta.setOnClickListener(v -> fetchBetaPif());
        mFetchEvolution.setOnClickListener(v -> fetchEvolutionPif());

        mImportButton.setOnClickListener(v -> {
            mImportTargetFileName = null;
            openPifFilePicker();
        });

        mShowPropsButton.setOnClickListener(v -> showCurrentProps());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        String activeConfig = mPifManager.getActiveConfigName();
        Map<String, String> props = mPifManager.getCurrentProperties();

        mActiveConfig.setText(activeConfig.isEmpty()
                ? getString(R.string.pif_no_config_loaded)
                : getString(R.string.pif_active_config, activeConfig));

        mModel.setText(formatKeyValue(R.string.pif_model_label,
                props.getOrDefault("MODEL", getString(R.string.pif_no_props))));

        mFingerprint.setText(formatKeyValue(R.string.pif_fingerprint_label,
                props.getOrDefault("FINGERPRINT", getString(R.string.pif_not_available))));

        mSecurityPatch.setText(formatKeyValue(R.string.pif_security_patch_label,
                props.getOrDefault("SECURITY_PATCH", getString(R.string.pif_not_available))));

        bindConfigCards();
    }

    private void fetchBetaPif() {
        Toast.makeText(requireContext(), R.string.pif_fetching, Toast.LENGTH_SHORT).show();
        mFetchBeta.setEnabled(false);

        new Thread(() -> {
            PifRepository.PifResult result = mPifRepository.fetchBetaPif();
            mHandler.post(() -> {
                mFetchBeta.setEnabled(true);
                if (result instanceof PifRepository.PifResult.Success) {
                    PifRepository.PifResult.Success success =
                            (PifRepository.PifResult.Success) result;
                    try {
                        String targetFile = mPifManager.writeAutoSelectedJsonConfig(success.pifData);
                        refreshUi();
                        Toast.makeText(requireContext(),
                                getString(R.string.pif_fetch_success_named, success.model, targetFile),
                                Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "applyPif error", e);
                        Toast.makeText(requireContext(),
                                getString(R.string.pif_fetch_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                } else if (result instanceof PifRepository.PifResult.Error) {
                    PifRepository.PifResult.Error error = (PifRepository.PifResult.Error) result;
                    Toast.makeText(requireContext(),
                            getString(R.string.pif_fetch_failed, error.message),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void fetchEvolutionPif() {
        Toast.makeText(requireContext(), R.string.pif_fetching_evolution, Toast.LENGTH_SHORT).show();
        mFetchEvolution.setEnabled(false);

        new Thread(() -> {
            PifRepository.PifResult result = mPifRepository.fetchEvolutionPif();
            mHandler.post(() -> {
                mFetchEvolution.setEnabled(true);
                if (result instanceof PifRepository.PifResult.Success) {
                    PifRepository.PifResult.Success success =
                            (PifRepository.PifResult.Success) result;
                    try {
                        String targetFile = mPifManager.writeAutoSelectedJsonConfig(success.pifData);
                        refreshUi();
                        Toast.makeText(requireContext(),
                                getString(R.string.pif_fetch_success_named, success.model, targetFile),
                                Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "writeEvolutionPif error", e);
                        Toast.makeText(requireContext(),
                                getString(R.string.pif_fetch_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                } else if (result instanceof PifRepository.PifResult.Error) {
                    PifRepository.PifResult.Error error = (PifRepository.PifResult.Error) result;
                    Toast.makeText(requireContext(),
                            getString(R.string.pif_fetch_failed, error.message),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void openPifFilePicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                new String[] {"application/json", "text/plain", "text/x-java-properties"});
        mPifFileLauncher.launch(intent);
    }

    private void importPifFile(@NonNull Uri uri) {
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IllegalStateException("Unable to open config");
            }

            String fileName = resolveDisplayName(uri);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String importedFile = mPifManager.importPifConfig(mImportTargetFileName, fileName, content);
            mImportTargetFileName = null;
            refreshUi();
            Toast.makeText(requireContext(),
                    getString(R.string.pif_import_success_named, importedFile),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            mImportTargetFileName = null;
            Log.e(TAG, "importPifFile error", e);
            Toast.makeText(requireContext(),
                    getString(R.string.pif_import_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showCurrentProps() {
        Map<String, String> props = mPifManager.getCurrentProperties();
        if (props.isEmpty()) {
            Toast.makeText(requireContext(), R.string.pif_no_props, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject json = new JSONObject(props);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_current_props_title)
                    .setMessage(json.toString(4).replace("\\/", "/"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showCurrentProps error", e);
        }
    }

    private String resolveDisplayName(@NonNull Uri uri) {
        Cursor cursor = null;
        try {
            cursor = requireContext().getContentResolver().query(
                    uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveDisplayName failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null ? lastSegment : "config";
    }

    private String formatKeyValue(int labelResId, @NonNull String value) {
        return getString(labelResId, value);
    }

    private void bindConfigCards() {
        if (mConfigContainer == null) {
            return;
        }

        mConfigContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<PifManager.ConfigState> states = mPifManager.getConfigStates();

        for (PifManager.ConfigState state : states) {
            View card = inflater.inflate(R.layout.item_pif_config, mConfigContainer, false);

            TextView fileName = card.findViewById(R.id.tv_pif_file_name);
            TextView status = card.findViewById(R.id.tv_pif_file_status);
            TextView summary = card.findViewById(R.id.tv_pif_file_summary);
            Button replace = card.findViewById(R.id.btn_pif_replace);
            Button delete = card.findViewById(R.id.btn_pif_delete);

            fileName.setText(state.fileName);
            status.setText(state.isActive
                    ? getString(R.string.pif_file_status_active)
                    : state.exists
                            ? getString(R.string.pif_file_status_available)
                            : getString(R.string.pif_file_status_empty));

            summary.setText(buildConfigSummary(state));
            delete.setEnabled(state.exists);

            replace.setOnClickListener(v -> {
                mImportTargetFileName = state.fileName;
                openPifFilePicker();
            });

            delete.setOnClickListener(v -> {
                mPifManager.deleteConfig(state.fileName);
                refreshUi();
                Toast.makeText(requireContext(),
                        getString(R.string.pif_deleted_named, state.fileName),
                        Toast.LENGTH_SHORT).show();
            });

            mConfigContainer.addView(card);
        }
    }

    private String buildConfigSummary(PifManager.ConfigState state) {
        if (!state.exists) {
            return getString(R.string.pif_file_empty_summary);
        }

        String model = state.data.get("MODEL");
        String patch = state.data.get("SECURITY_PATCH");

        if (model != null && patch != null && !patch.isEmpty()) {
            return getString(R.string.spoof_dashboard_pif_detail, model, patch);
        }
        if (model != null && !model.isEmpty()) {
            return model;
        }

        return getString(R.string.pif_file_present_summary);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }
}
