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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SubSettings;
import com.android.settings.core.InstrumentedFragment;

import java.util.Map;

public class Spoofing extends InstrumentedFragment {

    private PifManager mPifManager;
    private KeyboxManager mKeyboxManager;

    private TextView mPifStatus;
    private TextView mPifDetail;
    private TextView mTrickyStoreStatus;
    private TextView mTrickyStoreDetail;
    private TextView mAppSpoofStatus;
    private TextView mAppSpoofDetail;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().setTitle(R.string.spoof_screen_title);
        mPifManager = new PifManager(requireContext());
        mKeyboxManager = new KeyboxManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_spoofing_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPifStatus = view.findViewById(R.id.tv_pif_status);
        mPifDetail = view.findViewById(R.id.tv_pif_detail);
        mTrickyStoreStatus = view.findViewById(R.id.tv_trickystore_status);
        mTrickyStoreDetail = view.findViewById(R.id.tv_trickystore_detail);
        mAppSpoofStatus = view.findViewById(R.id.tv_app_spoof_status);
        mAppSpoofDetail = view.findViewById(R.id.tv_app_spoof_detail);

        view.findViewById(R.id.card_pif).setOnClickListener(v ->
                openFragment(PifFragment.class, getString(R.string.pif_category_title)));
        view.findViewById(R.id.card_trickystore).setOnClickListener(v ->
                openFragment(TrickyStoreFragment.class, getString(R.string.trickystore_screen_title)));
        view.findViewById(R.id.card_app_spoof).setOnClickListener(v ->
                openFragment(AppSpoofFragment.class, getString(R.string.game_spoofing_title)));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDashboard();
    }

    private void refreshDashboard() {
        bindPifSummary();
        bindTrickyStoreSummary();
        bindAppSpoofSummary();
    }

    private void bindPifSummary() {
        String activeConfig = mPifManager.getActiveConfigName();
        Map<String, String> props = mPifManager.getCurrentProperties();
        String model = props.get("MODEL");
        String patch = props.get("SECURITY_PATCH");

        if (activeConfig.isEmpty()) {
            mPifStatus.setText(R.string.pif_no_config_loaded);
            mPifDetail.setText(R.string.spoof_dashboard_pif_empty_detail);
            return;
        }

        mPifStatus.setText(getString(R.string.pif_active_config, activeConfig));
        if (model == null || model.isEmpty()) {
            model = getString(R.string.pif_no_props);
        }
        if (patch == null || patch.isEmpty()) {
            mPifDetail.setText(model);
        } else {
            mPifDetail.setText(getString(R.string.spoof_dashboard_pif_detail, model, patch));
        }
    }

    private void bindTrickyStoreSummary() {
        boolean keyboxExists = mKeyboxManager.keyboxExists();
        int targetCount = mKeyboxManager.getTargetAppCount();

        mTrickyStoreStatus.setText(keyboxExists
                ? getString(R.string.keybox_installed)
                : getString(R.string.keybox_not_found));
        mTrickyStoreDetail.setText(targetCount > 0
                ? getString(R.string.target_apps_count, targetCount)
                : getString(R.string.spoof_dashboard_target_empty_detail));
    }

    private void bindAppSpoofSummary() {
        boolean enabled = AppSpoofFragment.isConfigEnabled(requireContext());
        int appCount = AppSpoofFragment.getConfiguredAppCount(requireContext());

        mAppSpoofStatus.setText(enabled
                ? getString(R.string.game_spoofing_enabled)
                : getString(R.string.game_spoofing_disabled));
        mAppSpoofDetail.setText(appCount > 0
                ? getString(R.string.game_spoofing_configured_count, appCount)
                : getString(R.string.game_spoof_no_games));
    }

    private void openFragment(@NonNull Class<? extends Fragment> fragmentClass,
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
