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

import android.animation.Animator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Pair
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BaseActivity
import com.android.launcher3.ExtendedEditText
import com.android.launcher3.Insettable
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.anim.AnimationSuccessListener
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.tally.search.TallySearchRanking.Found
import com.android.launcher3.tally.search.TallySearchRanking.Kind
import com.android.launcher3.touch.ItemClickHandler
import com.android.launcher3.views.BaseDragLayer
import java.time.ZonedDateTime
import kotlin.math.max

/**
 * Home's search, as the design's "Search, open": a sheet over Home with the search field at the top
 * and the keyboard up, and what was found in sections: Top (the best of all, or a quick answer),
 * Apps (apps, then their shortcuts), Settings (pages), and Search in (hand-offs to the apps that
 * search contacts, files and the web with the typed words). The search slot under the dock opens it
 * ([com.android.launcher3.tally.home.TallySearchSlot]); All apps keeps its own search.
 *
 * It searches only what Launcher already has or is given for it ([TallySearchSession]); it reads no
 * contacts, files or messages and sends nothing anywhere until a web hand-off is tapped. The typed
 * words are never logged nor kept: closing the sheet clears them, and it closes whenever Home stops
 * (an app opens, the screen locks), so it never shows over the lock screen or on return.
 *
 * Rows are at least 56 dp tall (48 dp targets and more), section titles are headings for screen
 * readers, and the sheet keeps a screen reader inside it while open.
 */
class TallySearchSheet(context: Context) :
    AbstractFloatingView(context, null), Insettable, TallySearchSession.Listener {

    private val launcher: Launcher = Launcher.getLauncher(context)
    private val density = resources.displayMetrics.density
    private val ink = context.getColor(R.color.tally_ink)
    private val inkMuted = context.getColor(R.color.tally_ink_muted)
    private val surface = context.getColor(R.color.tally_surface)
    private val outlineVariant = context.getColor(R.color.tally_outline_variant)
    private val radius = resources.getDimension(R.dimen.tally_radius_m)
    private val hairline = resources.getDimension(R.dimen.tally_stroke_hairline)
    private val spaceXs = resources.getDimensionPixelSize(R.dimen.tally_space_xs)
    private val spaceS = resources.getDimensionPixelSize(R.dimen.tally_space_s)
    private val spaceM = resources.getDimensionPixelSize(R.dimen.tally_space_m)
    private val spaceL = resources.getDimensionPixelSize(R.dimen.tally_space_l)
    private val rowMinHeight = resources.getDimensionPixelSize(R.dimen.tally_row_min_height)
    private val fieldHeight = resources.getDimensionPixelSize(R.dimen.tally_target_min)
    private val iconSize = (ICON_DP * density).toInt()
    private val glyphSize = resources.getDimensionPixelSize(R.dimen.tally_icon_size)

    private val field: ExtendedEditText
    private val list: LinearLayout
    private val scroll: ScrollView
    private val session = TallySearchSession(context, this)
    private val systemInsets = Rect()
    private var imeBottom = 0
    private val closeOnStop = Runnable { close(false) }

    /** The typed words, and what was found for them (or for the words just before). */
    private var query = ""
    private var appsFound: TallySearchSession.AppsFound? = null
    private var pages: List<TallySettingsSearch.Page> = emptyList()
    private var targets: Map<TallyHandoffs.Kind, TallyHandoffs.Target> = emptyMap()
    private var answer: TallyAnswers.Answer? = null
    /** What the keyboard's search key opens: the top row. */
    private var openTop: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(
            ColorUtils.setAlphaComponent(context.getColor(R.color.tally_background), SHEET_ALPHA)
        )
        accessibilityPaneTitle = context.getString(android.R.string.search_go)

        // The field: a key-like box (surface, hairline, r12) with the search icon, as the slot.
        val box =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(spaceL, 0, spaceL, 0)
                background =
                    GradientDrawable().apply {
                        setColor(surface)
                        setStroke(max(1, hairline.toInt()), outlineVariant)
                        cornerRadius = radius
                    }
            }
        box.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_allapps_search)
                imageTintList = ColorStateList.valueOf(ink)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(glyphSize, glyphSize),
        )
        field =
            ExtendedEditText(context).apply {
                background = null
                setTextAppearance(R.style.TextAppearance_Tally_Item)
                setTextColor(ink)
                setHintTextColor(inkMuted)
                hint = context.getString(android.R.string.search_go)
                isSingleLine = true
                maxLines = 1
                // As All apps' search field, without capitals; and no learning from the words.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions =
                    EditorInfo.IME_ACTION_SEARCH or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                filters = arrayOf(InputFilter.LengthFilter(TallyHandoffs.MAX_QUERY_LENGTH))
                textCursorDrawable =
                    GradientDrawable().apply {
                        setColor(context.getColor(R.color.tally_accent))
                        setSize((CARET_DP * density).toInt().coerceAtLeast(1), 0)
                    }
                setPaddingRelative(spaceM, 0, 0, 0)
            }
        box.addView(field, LayoutParams(0, MATCH_PARENT, 1f))
        addView(box, LayoutParams(MATCH_PARENT, fieldHeight))

        list = LinearLayout(context).apply { orientation = VERTICAL }
        scroll =
            ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                addView(list, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                // Dragging the results puts the keyboard away, as in All apps' search. (The scroll
                // view only sees the moves once it scrolls; a tap on a row stays the row's.)
                setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_MOVE && field.hasFocus()) {
                        field.hideKeyboard()
                    }
                    false
                }
            }
        addView(scroll, LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = spaceS })

        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    onQueryChanged(s?.toString().orEmpty())
                }
            }
        )
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                openTop?.invoke()
                true
            } else {
                false
            }
        }
        updatePadding()
    }

    private fun open() {
        mIsOpen = true
        launcher.dragLayer.addView(this, BaseDragLayer.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        launcher.addEventCallback(BaseActivity.EVENT_STOPPED, closeOnStop)
        session.start()
        alpha = 0f
        animate().alpha(1f).setDuration(OPEN_MS).start()
        post { if (mIsOpen) field.showKeyboard() }
        announceAccessibilityChanges()
    }

    override fun handleClose(animate: Boolean) {
        if (!mIsOpen) return
        mIsOpen = false
        launcher.removeEventCallback(BaseActivity.EVENT_STOPPED, closeOnStop)
        session.close()
        if (field.hasFocus()) field.hideKeyboard()
        // The words go with the sheet.
        field.text?.clear()
        if (animate) {
            animate().alpha(0f).setDuration(CLOSE_MS).withEndAction(::removeFromParent).start()
        } else {
            removeFromParent()
        }
    }

    private fun removeFromParent() {
        animate().cancel()
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_TALLY_SEARCH != 0

    // Touches go to the sheet's own views; nothing behind it takes them.
    override fun onControllerInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    override fun getAccessibilityTarget(): Pair<View, String> =
        Pair.create<View, String>(this, context.getString(android.R.string.search_go))

    override fun getAccessibilityInitialFocusView(): View = field

    override fun setInsets(insets: Rect) {
        systemInsets.set(insets)
        updatePadding()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        imeBottom =
            if (insets.isVisible(WindowInsets.Type.ime())) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                0
            }
        updatePadding()
        return insets
    }

    private fun updatePadding() {
        setPaddingRelative(
            spaceL,
            systemInsets.top + spaceS,
            spaceL,
            max(imeBottom, systemInsets.bottom) + spaceS,
        )
    }

    private fun onQueryChanged(text: String) {
        if (!mIsOpen) return
        query = text
        answer = TallyAnswers.answer(text, answerContext())
        if (text.isBlank()) {
            appsFound = null
            pages = emptyList()
        }
        session.search(text)
        render()
    }

    private fun answerContext(): TallyAnswers.Context {
        val locale = resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return TallyAnswers.Context(
            locale,
            ZonedDateTime.now(),
            DateFormat.getBestDateTimePattern(locale, skeleton),
            DateFormat.getBestDateTimePattern(locale, "EEEMMMd"),
        )
    }

    override fun onAppsFound(query: String, found: TallySearchSession.AppsFound) {
        if (!mIsOpen || this.query.isBlank()) return
        appsFound = found
        render()
    }

    override fun onPagesFound(query: String, pages: List<TallySettingsSearch.Page>) {
        if (!mIsOpen || this.query.isBlank()) return
        this.pages = pages
        render()
    }

    override fun onHandoffTargets(targets: Map<TallyHandoffs.Kind, TallyHandoffs.Target>) {
        if (!mIsOpen) return
        this.targets = targets
        render()
    }

    /** Lays the sections out for the words and what was found. */
    private fun render() {
        list.removeAllViews()
        openTop = null
        val text = query.trim()
        if (text.isEmpty()) return

        val found = appsFound
        val apps = found?.apps.orEmpty().map { Found<Any>(it.item, it.title, it.kind, it.match) }
        val shortcuts =
            found?.shortcuts.orEmpty().map { Found<Any>(it.item, it.title, it.kind, it.match) }
        val settings =
            pages.map { page ->
                val match =
                    TallySearchRanking.match(text, page.title) ?: TallySearchRanking.Match.OTHER
                Found<Any>(page, page.title, Kind.SETTING, match)
            }
        val currentAnswer = answer
        val sections = TallySearchRanking.sections(apps, shortcuts, settings, currentAnswer != null)
        val settingsIcon =
            found?.settingsApp?.newIcon(context)
                ?: context.getDrawable(R.drawable.tally_ic_search_settings)?.apply {
                    setTint(inkMuted)
                }

        if (currentAnswer != null) {
            addHeader(R.string.tally_search_top)
            addAnswer(currentAnswer)
        } else {
            sections.top?.let { top ->
                addHeader(R.string.tally_search_top)
                addFound(top, settingsIcon, isTop = true)
            }
        }
        val privateSpace = found?.privateSpace == true
        if (sections.apps.isNotEmpty() || sections.shortcuts.isNotEmpty() || privateSpace) {
            addHeader(R.string.tally_search_apps)
            if (privateSpace) addPrivateSpace()
            sections.apps.forEach { addFound(it, settingsIcon, isTop = false) }
            sections.shortcuts.forEach { addFound(it, settingsIcon, isTop = false) }
        }
        if (sections.settings.isNotEmpty()) {
            addHeader(R.string.tally_search_settings)
            sections.settings.forEach { addFound(it, settingsIcon, isTop = false) }
        }
        val handoffs = TallyHandoffs.handoffs(text, targets)
        if (handoffs.isNotEmpty()) {
            addHeader(R.string.tally_search_in)
            handoffs.forEach(::addHandoff)
        }
    }

    private fun addHeader(title: Int) {
        list.addView(
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Section)
                setTextColor(inkMuted)
                setText(title)
                isAccessibilityHeading = true
                setPaddingRelative(spaceS, spaceM, spaceS, spaceXs)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    private fun addFound(found: Found<Any>, settingsIcon: Drawable?, isTop: Boolean) {
        when (val item = found.item) {
            is AppInfo ->
                addRow(item.newIcon(context), null, found.title, null, isTop) { icon ->
                    icon.tag = item
                    ItemClickHandler.INSTANCE.onClick(icon)
                }
            is TallySearchSession.ShortcutFound ->
                addRow(item.info.newIcon(context), null, item.label, item.appTitle, isTop) { icon ->
                    icon.tag = item.info
                    ItemClickHandler.INSTANCE.onClick(icon)
                }
            is TallySettingsSearch.Page -> {
                val place = item.parent ?: context.getString(R.string.tally_search_settings)
                addRow(settingsIcon, null, item.title, place, isTop) { icon ->
                    launcher.startActivitySafely(icon, session.pageIntent(item), null)
                }
            }
        }
    }

    private fun addPrivateSpace() {
        addRow(
            context.getDrawable(R.drawable.ic_private_space_with_background),
            null,
            context.getString(R.string.private_space_label),
            context.getString(R.string.private_space_secondary_label),
            isTop = false,
        ) {
            // All apps' private space entry does the rest (unlock, or set up).
            close(false)
            launcher.stateManager.goToState(
                LauncherState.ALL_APPS,
                true,
                object : AnimationSuccessListener() {
                    override fun onAnimationSuccess(animator: Animator) {
                        launcher.appsView.privateProfileManager?.openPrivateSpaceFromSearch(
                            launcher.appsView
                        )
                    }
                },
            )
        }
    }

    private fun addAnswer(answer: TallyAnswers.Answer) {
        val row =
            addRow(
                context.getDrawable(R.drawable.tally_ic_search_answer),
                inkMuted,
                answer.value,
                answer.detail,
                isTop = true,
            ) {
                context
                    .getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(answer.detail, answer.value))
            }
        row.setClickLabel(context.getString(android.R.string.copy))
    }

    private fun addHandoff(handoff: TallyHandoffs.Handoff) {
        val glyphRes =
            when (handoff.kind) {
                TallyHandoffs.Kind.CONTACTS -> R.drawable.tally_ic_search_contacts
                TallyHandoffs.Kind.FILES -> R.drawable.tally_ic_search_files
                TallyHandoffs.Kind.WEB -> R.drawable.tally_ic_search_web
            }
        val label =
            context.getString(R.string.tally_search_handoff, handoff.target.label, handoff.query)
        val intent = TallySearchSession.handoffIntent(handoff)
        addRow(context.getDrawable(glyphRes), inkMuted, label, null, isTop = false, glyph = true) {
            icon ->
            launcher.startActivitySafely(icon, intent, null)
        }
    }

    /**
     * Adds a row: [icon] (tinted [tint] when given), [title], and [subtitle] under it; the top row
     * on a key-like surface. A tap runs [onTap] with the icon, which launches animate from.
     */
    private fun addRow(
        icon: Drawable?,
        tint: Int?,
        title: CharSequence,
        subtitle: CharSequence?,
        isTop: Boolean,
        glyph: Boolean = false,
        onTap: (View) -> Unit,
    ): View {
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowMinHeight
                setPaddingRelative(spaceS, spaceXs, spaceS, spaceXs)
                isClickable = true
                isFocusable = true
                background = rowBackground(isTop)
            }
        val iconView =
            ImageView(context).apply {
                setImageDrawable(icon)
                tint?.let { imageTintList = ColorStateList.valueOf(it) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        // A glyph sits centred in an icon's room, so the words line up.
        val box = if (glyph) glyphSize else iconSize
        row.addView(
            iconView,
            LinearLayout.LayoutParams(box, box).apply {
                if (glyph) {
                    val inset = (iconSize - glyphSize) / 2
                    marginStart = inset
                    marginEnd = inset
                }
            },
        )
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(
            TextView(context).apply {
                setTextAppearance(
                    if (isTop) R.style.TextAppearance_Tally_ItemEmphasis
                    else R.style.TextAppearance_Tally_Item
                )
                setTextColor(ink)
                text = title
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        if (!subtitle.isNullOrEmpty()) {
            texts.addView(
                TextView(context).apply {
                    setTextAppearance(R.style.TextAppearance_Tally_Caption)
                    setTextColor(inkMuted)
                    text = subtitle
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
        }
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = spaceM },
        )
        row.setOnClickListener { onTap(iconView) }
        list.addView(
            row,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (isTop) bottomMargin = spaceXs
            },
        )
        if (openTop == null) openTop = { row.performClick() }
        return row
    }

    /** A row's press ripple, in r12; the top row also on a surface with a hairline. */
    private fun rowBackground(isTop: Boolean): Drawable {
        val mask =
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = radius
            }
        val content =
            if (!isTop) null
            else
                GradientDrawable().apply {
                    setColor(surface)
                    setStroke(max(1, hairline.toInt()), outlineVariant)
                    cornerRadius = radius
                }
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, PRESS_ALPHA)),
            content,
            mask,
        )
    }

    /** Names what a tap on this view does, for screen readers ("double-tap to copy"). */
    private fun View.setClickLabel(label: CharSequence) {
        accessibilityDelegate =
            object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label))
                }
            }
    }

    companion object {
        /** The size of an app's or a page's icon in a row (dp). */
        private const val ICON_DP = 40f
        /** The caret's width (dp). */
        private const val CARET_DP = 2f
        /** The sheet over Home: the background at 97 %, as the design's. */
        private const val SHEET_ALPHA = 0xF7
        /** A row's press layer: the ink at 12 %. */
        private const val PRESS_ALPHA = 0x1F
        private const val OPEN_MS = 150L
        private const val CLOSE_MS = 100L
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Opens Home's search over [launcher]'s Home, unless it is open. */
        @JvmStatic
        fun show(launcher: Launcher) {
            val open =
                AbstractFloatingView.getOpenView<TallySearchSheet>(
                    launcher,
                    AbstractFloatingView.TYPE_TALLY_SEARCH,
                )
            if (open != null) return
            TallySearchSheet(launcher).open()
        }
    }
}
