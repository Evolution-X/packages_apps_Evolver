/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
package org.evolution.settings.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Settings-side duplicate of com.android.systemui.nowplaying.ambient.PixelAmbientIndicationDetector.
 * SystemUI and Settings build as separate targets, so this can't be shared via import — keep this
 * logic identical to the SystemUI copy if either one changes.
 */
object PixelAmbientIndicationDetector {

    private const val AS_PACKAGE = "com.google.android.as"

    fun shouldUseNativeAmbientIndication(context: Context): Boolean {
        val isPixelBrand = Build.BRAND.equals("google", ignoreCase = true) &&
            Build.MANUFACTURER.equals("google", ignoreCase = true)
        if (!isPixelBrand) return false

        return try {
            context.packageManager.getApplicationInfo(AS_PACKAGE, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
