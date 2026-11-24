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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import org.junit.Test;

public class TaskIconsTest {
    @Test
    public void taskProfileControlsResolutionAndMissingIconsClear() throws Exception {
        final Context context = mock(Context.class);
        final Context profile = mock(Context.class);
        final PackageManager pm = mock(PackageManager.class);
        when(context.createContextAsUser(UserHandle.of(10), 0)).thenReturn(profile);
        when(profile.getPackageManager()).thenReturn(pm);
        final ActivityManager.RunningTaskInfo task = new ActivityManager.RunningTaskInfo();
        task.userId = 10;
        task.baseActivity = new ComponentName("example.app", "Main");
        final ActivityInfo info = mock(ActivityInfo.class);
        final Drawable icon = mock(Drawable.class);
        when(pm.getActivityInfo(eq(task.baseActivity), any(PackageManager.ComponentInfoFlags.class)))
                .thenReturn(info);
        when(info.loadIcon(pm)).thenReturn(icon);
        assertSame(icon, TaskIcons.load(context, task));
        verify(context, never()).getPackageManager();
        when(pm.getActivityInfo(eq(task.baseActivity), any(PackageManager.ComponentInfoFlags.class)))
                .thenThrow(new PackageManager.NameNotFoundException());
        assertNull(TaskIcons.load(context, task));
    }
}
