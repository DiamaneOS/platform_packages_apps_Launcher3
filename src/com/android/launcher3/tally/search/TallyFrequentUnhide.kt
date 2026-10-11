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
import androidx.preference.Preference
import com.android.launcher3.R

/** Home settings' "Show hidden items again" row. Its summary gives the number, never a name. */
class TallyFrequentUnhide @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    Preference(context, attrs) {

    private val watcher: (Int) -> Unit = ::show

    init {
        // Hidden until the number is known.
        isVisible = false
    }

    override fun onAttached() {
        super.onAttached()
        TallyFrequentStore.watchHidden(context, watcher)
    }

    override fun onDetached() {
        TallyFrequentStore.unwatchHidden(watcher)
        super.onDetached()
    }

    override fun onClick() {
        TallyFrequentStore.unhide(context)
    }

    private fun show(count: Int) {
        isVisible = count > 0
        summary =
            if (count > 0) {
                context.resources.getQuantityString(R.plurals.tally_frequent_hidden, count, count)
            } else {
                null
            }
    }
}
