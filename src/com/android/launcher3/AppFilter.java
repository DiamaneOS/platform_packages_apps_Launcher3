package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;

import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.tally.moments.MomentsHome;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    private final Set<ComponentName> mFilteredComponents;

    @Inject
    public AppFilter(@ApplicationContext Context context) {
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
        MomentsHome.init(context);
    }

    public boolean shouldShowApp(ComponentName app) {
        return !mFilteredComponents.contains(app);
    }

    /** Whether All apps lists {@code app} of {@code user}; Moments narrows it while it is on. */
    public boolean shouldShowApp(ComponentName app, UserHandle user) {
        return shouldShowApp(app) && MomentsHome.shouldShowApp(app, user);
    }
}
