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

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.R
import com.android.launcher3.util.SafeCloseable
import com.android.systemui.shared.recents.model.Task
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The "Still running · Stop" row: its Stop key refuses touches through a window over it. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyStillRunningViewTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stopped = mutableListOf<StoppableApp>()
    private val controller =
        TallyStillRunning(
            listen = { SafeCloseable {} },
            stopApp = { stopped += it },
            loadLabel = { if (it == MUSIC) "Music" else "Downloads" },
            uiExecutor = Executor { it.run() },
            backgroundExecutor = Executor { it.run() },
        )
    private lateinit var row: TallyStillRunningView
    private lateinit var stopKey: TextView

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            row =
                LayoutInflater.from(context).inflate(R.layout.tally_recents_still_running, null)
                    as TallyStillRunningView
            stopKey = row.findViewById(R.id.tally_still_running_stop)
            row.setController(controller)
            // The row is not in a window here, so it is not added by attaching: add it directly.
            controller.addRowView(row)
            controller.setRecentsShowing(this, true)
            controller.listener.onStoppableAppsChanged(
                arrayOf(MUSIC.packageName, DOWNLOADS.packageName),
                intArrayOf(MUSIC.userId, DOWNLOADS.userId),
            )
            controller.onTasksRemoved(listOf(task(MUSIC)))
            row.measure(
                MeasureSpec.makeMeasureSpec(dp(372), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        // Past the moment in which a row that just appeared takes no Stop.
        SystemClock.sleep(TAP_GUARD_WAIT_MS)
    }

    @Test
    fun rowAndStopKey_filterTouchesThroughAWindowOverThem() {
        assertThat(row.filterTouchesWhenObscured).isTrue()
        assertThat(stopKey.filterTouchesWhenObscured).isTrue()
    }

    @Test
    fun obscuredTouch_neverReachesTheStopKey() {
        instrumentation.runOnMainSync {
            val taken = touch(row, MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            assertThat(taken).isTrue() // the row swallows it, so nothing behind it goes Home
            assertThat(stopKey.isPressed).isFalse()
            touch(row, MotionEvent.ACTION_UP, MotionEvent.FLAG_WINDOW_IS_OBSCURED)

            assertThat(touch(stopKey, MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED))
                .isFalse()
            assertThat(stopKey.isPressed).isFalse()
        }
        assertThat(stopped).isEmpty()
    }

    @Test
    fun unobscuredTouch_pressesTheStopKey_andItsClickStopsTheSwipedApp() {
        instrumentation.runOnMainSync {
            assertThat(touch(row, MotionEvent.ACTION_DOWN, 0)).isTrue()
            assertThat(stopKey.isPressed).isTrue()
            touch(row, MotionEvent.ACTION_CANCEL, 0)
            stopKey.performClick()
        }

        assertThat(stopped).containsExactly(MUSIC)
        assertThat(row.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun stopKeyTarget_isAtLeast48dp() {
        assertThat(stopKey.width).isAtLeast(dp(48))
        assertThat(stopKey.height).isAtLeast(dp(48))
        assertThat(row.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun rowThatJustChanged_takesNoStop_untilAMomentLater() {
        instrumentation.runOnMainSync {
            controller.onTasksRemoved(listOf(task(DOWNLOADS)))
            stopKey.performClick()
        }
        assertThat(stopped).isEmpty()

        SystemClock.sleep(TAP_GUARD_WAIT_MS)
        instrumentation.runOnMainSync { stopKey.performClick() }

        assertThat(stopped).containsExactly(DOWNLOADS)
    }

    /** A touch at the Stop key's centre, sent to [target] in the row's coordinates. */
    private fun touch(target: View, action: Int, flags: Int): Boolean {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coords =
            MotionEvent.PointerCoords().apply {
                val inRow = target === row
                x = stopKey.width / 2f + if (inRow) stopKey.left else 0
                y = stopKey.height / 2f + if (inRow) stopKey.top else 0
                pressure = 1f
                size = 1f
            }
        val now = SystemClock.uptimeMillis()
        val event =
            MotionEvent.obtain(
                now,
                now,
                action,
                1,
                arrayOf(properties),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                flags,
            )
        return target.dispatchTouchEvent(event).also { event.recycle() }
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics,
            )
            .toInt()

    private fun task(app: StoppableApp): Task {
        val intent =
            Intent().setComponent(ComponentName(app.packageName, "${app.packageName}.Main"))
        return Task(Task.TaskKey(app.hashCode(), 1, intent, null, app.userId, 0L))
    }

    private companion object {
        const val TAP_GUARD_WAIT_MS = 600L
        val MUSIC = StoppableApp("com.example.music", 0)
        val DOWNLOADS = StoppableApp("com.example.downloads", 0)
    }
}
