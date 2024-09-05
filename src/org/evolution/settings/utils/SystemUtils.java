/*
 * SPDX-FileCopyrightText: 2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.IActivityManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;

import com.android.settings.R;

public class SystemUtils {

    public static void showSystemUiRestartDialog(final Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.systemui_restart_title)
                .setMessage(R.string.systemui_restart_message)
                .setPositiveButton(R.string.systemui_restart_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                try {
                                    ActivityManager am =
                                            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                                    IActivityManager ams = ActivityManager.getService();
                                    for (ActivityManager.RunningAppProcessInfo app : am.getRunningAppProcesses()) {
                                        if ("com.android.systemui".equals(app.processName)) {
                                            ams.killApplicationProcess(app.processName, app.uid);
                                            break;
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                return null;
                            }
                        }.execute();
                    }
                })
                .setNegativeButton(R.string.systemui_restart_cancel, null)
                .show();
    }
}
