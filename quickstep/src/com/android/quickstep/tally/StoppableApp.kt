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

package com.android.quickstep.tally

/**
 * An app that SystemUI's Active apps dialog lists with a Stop button: [packageName] in user
 * [userId]. Recents' "Still running · Stop" (Tally) offers Stop only for these.
 */
data class StoppableApp(val packageName: String, val userId: Int) {

    companion object {
        /** SystemUI sends a few apps at most; anything larger is not a list to trust. */
        const val MAX_APPS = 1000

        /**
         * The whole set SystemUI sent through `IStoppableAppsListener`: app i is `packageNames[i]`
         * in user `userIds[i]`. Arrays that do not pair up give the empty set, so nothing is
         * offered; entries without a package name are left out.
         */
        fun setOf(packageNames: Array<String?>?, userIds: IntArray?): Set<StoppableApp> {
            if (
                packageNames == null ||
                    userIds == null ||
                    packageNames.size != userIds.size ||
                    packageNames.size > MAX_APPS
            ) {
                return emptySet()
            }
            val apps = HashSet<StoppableApp>(packageNames.size)
            for (i in packageNames.indices) {
                val packageName = packageNames[i]
                if (!packageName.isNullOrEmpty()) apps.add(StoppableApp(packageName, userIds[i]))
            }
            return apps
        }
    }
}
