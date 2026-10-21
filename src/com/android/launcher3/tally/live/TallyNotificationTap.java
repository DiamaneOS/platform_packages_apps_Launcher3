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

package com.android.launcher3.tally.live;

import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

import com.android.launcher3.dagger.LauncherComponentProvider;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.util.UserIconInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Live and failed apps, from the notifications Launcher's NotificationListener gets for its dots.
 * Worker thread only.
 */
public final class TallyNotificationTap {
    private final NotificationListenerService mService;
    private final Consumer<Predicate<PackageUserKey>> mDispatchUpdate;
    private @Nullable TallyLiveTracker mTallyLive;
    private @Nullable TallyAppLabels mTallyLabels;
    private final Ranking mTallyRanking = new Ranking();

    /** {@code dispatchUpdate} redraws the keys of the apps it matches. */
    public TallyNotificationTap(NotificationListenerService service,
            Consumer<Predicate<PackageUserKey>> dispatchUpdate) {
        mService = service;
        mDispatchUpdate = dispatchUpdate;
    }

    public void onPosted(StatusBarNotification sbn) {
        dispatchTallyUpdate(tallyLive().onPosted(tallyInput(sbn)));
    }

    public void onRemoved(StatusBarNotification sbn) {
        dispatchTallyUpdate(tallyLive().onRemoved(sbn.getKey()));
    }

    /** Every active notification, after the listener reconnected or refreshed. */
    public void onFullRefresh(StatusBarNotification[] active) {
        if (mTallyLabels != null) {
            mTallyLabels.clear();
        }
        dispatchTallyUpdate(tallyLive().onAll(tallyInputs(active)));
    }

    /** The ranked notifications, after a ranking update. */
    public void onRankingUpdate(StatusBarNotification[] ranked) {
        dispatchTallyUpdate(tallyLive().onAll(tallyInputs(ranked)));
    }

    /** Names the tallies again, in the language of the moment. */
    public void onRelabel() {
        if (mTallyLive != null) {
            dispatchTallyUpdate(mTallyLive.relabel());
        }
    }

    /** Redraws the keys of the apps whose LED changed. */
    private void dispatchTallyUpdate(Set<PackageUserKey> changedLeds) {
        if (!changedLeds.isEmpty()) {
            mDispatchUpdate.accept(changedLeds::contains);
        }
    }

    private TallyLiveTracker tallyLive() {
        if (mTallyLive == null) {
            TallyAppLabels labels = new TallyAppLabels(mService);
            UserCache userCache = UserCache.getInstance(mService);
            mTallyLabels = labels;
            mTallyLive = new TallyLiveTracker(
                    LauncherComponentProvider.get(mService).getNotificationRepository().getLive(),
                    labels::labelOf,
                    labels::isSystemClock,
                    key -> {
                        if (key.mUser == null) {
                            return TallyLiveTracker.Profile.PERSONAL;
                        }
                        // A user the cache does not know yet (a profile being added, or a failed
                        // read) shows nothing, as the private space, rather than falling back to
                        // the main user's name and readout.
                        UserCache.CachedUserInfo cached =
                                userCache.getUserManagerState().getCachedInfoOrNull(key.mUser);
                        if (cached == null) {
                            return TallyLiveTracker.Profile.PRIVATE;
                        }
                        UserIconInfo info = cached.getIconInfo();
                        return TallyLiveTracker.Profile.of(info.isWork(), info.isPrivate());
                    });
        }
        return mTallyLive;
    }

    private TallyLiveTracker.Input tallyInput(StatusBarNotification sbn) {
        RankingMap rankings = mService.getCurrentRanking();
        boolean ranked = rankings != null && rankings.getRanking(sbn.getKey(), mTallyRanking);
        ApiWrapper apiWrapper = ApiWrapper.INSTANCE.get(mService);
        return TallyLiveTracker.Input.from(sbn, ranked ? mTallyRanking : null,
                apiWrapper::isImportanceLockedByCriticalDeviceFunction);
    }

    private List<TallyLiveTracker.Input> tallyInputs(StatusBarNotification[] notifications) {
        List<TallyLiveTracker.Input> inputs = new ArrayList<>(notifications.length);
        for (StatusBarNotification sbn : notifications) {
            inputs.add(tallyInput(sbn));
        }
        return inputs;
    }
}
