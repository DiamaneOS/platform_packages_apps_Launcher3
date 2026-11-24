/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.quickstep.split;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** Loads a task activity icon in the task's authenticated Android user. */
final class TaskIcons {
    private TaskIcons() { }

    static Drawable load(Context context, ActivityManager.RunningTaskInfo task) {
        if (task == null || task.baseActivity == null || task.userId < 0) return null;
        try {
            final Context source = context.createContextAsUser(UserHandle.of(task.userId), 0);
            final PackageManager pm = source.getPackageManager();
            return pm.getActivityInfo(task.baseActivity,
                    PackageManager.ComponentInfoFlags.of(0)).loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            return null;
        }
    }
}
