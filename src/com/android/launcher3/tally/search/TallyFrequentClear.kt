/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.launcher3.tally.search

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import com.android.launcher3.R

/** Home settings' "Clear suggestions" row: deletes every count and every "Don't suggest". */
class TallyFrequentClear @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    Preference(context, attrs) {

    override fun onClick() {
        TallyFrequentStore.clear(context)
        Toast.makeText(context, R.string.tally_frequent_cleared, Toast.LENGTH_SHORT).show()
    }
}
