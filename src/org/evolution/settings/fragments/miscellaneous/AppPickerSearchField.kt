/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settings.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String = stringResource(R.string.action_search_apps),
) {
    ExpressiveTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = placeholder,
        enabled = enabled,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_clear_search),
                    )
                }
            }
        },
        shape = SettingsShape.CornerFull,
        containerColor = MaterialTheme.colorScheme.surfaceBright,
    )
}
