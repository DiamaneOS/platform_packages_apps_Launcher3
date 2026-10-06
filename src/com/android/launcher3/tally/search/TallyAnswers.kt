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

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Quick answers for Home's search, all worked out on the phone with nothing sent anywhere: a time
 * in another place ([TallyZoneTimes]), a unit conversion ([TallyUnits]) or a sum
 * ([TallyArithmetic]), tried in that order. Pure Kotlin; the Android side passes the language, the
 * time now and its own time and date patterns.
 */
object TallyAnswers {
    /** Significant digits of a sum's result. */
    private const val SUM_DIGITS = 12
    /** Significant digits of a converted value. */
    private const val CONVERSION_DIGITS = 6
    private val LARGE = BigDecimal("1E15")
    private val SMALL = BigDecimal("1E-6")

    enum class Kind {
        SUM,
        CONVERSION,
        TIME,
    }

    /** An answer: its [value] ("48", "3.10686 mi", "18:30") and what it answers ([detail]). */
    data class Answer(val kind: Kind, val value: String, val detail: String)

    /**
     * What an answer is worked out with: the [locale] (its decimal separator, digits and names),
     * the time [now] here, and the [timePattern] and [datePattern] to show a time and its day in
     * (Android's best patterns for the language and the 12/24-hour setting).
     */
    class Context(
        val locale: Locale,
        val now: ZonedDateTime,
        val timePattern: String = "HH:mm",
        val datePattern: String = "EEE d MMM",
    )

    /** The answer to [query], or null when it asks none of these. */
    @JvmStatic
    fun answer(query: String, context: Context): Answer? {
        val text = query.trim()
        if (text.isEmpty() || text.length > TallyArithmetic.MAX_LENGTH) return null
        val separator = DecimalFormatSymbols.getInstance(context.locale).decimalSeparator

        TallyZoneTimes.answer(text, context.now)?.let { zoneTime ->
            val time = DateTimeFormatter.ofPattern(context.timePattern, context.locale)
            val date = DateTimeFormatter.ofPattern(context.datePattern, context.locale)
            return Answer(
                Kind.TIME,
                zoneTime.time.format(time),
                "${zoneTime.place.name} · ${zoneTime.time.format(date)}",
            )
        }
        TallyUnits.convert(text, separator)?.let { conversion ->
            val result = format(BigDecimal(conversion.result), context.locale, CONVERSION_DIGITS)
            val value = format(conversion.value, context.locale, SUM_DIGITS)
            return Answer(
                Kind.CONVERSION,
                "$result ${conversion.to.symbol}",
                "$value ${conversion.from.symbol}",
            )
        }
        TallyArithmetic.evaluate(text, separator)?.let { sum ->
            return Answer(Kind.SUM, format(sum, context.locale, SUM_DIGITS), "$text =")
        }
        return null
    }

    /**
     * [value] to [digits] significant digits in [locale]'s digits, with grouping; very large or
     * small values in scientific notation.
     */
    @JvmStatic
    fun format(value: BigDecimal, locale: Locale, digits: Int): String {
        val rounded = value.round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val symbols = DecimalFormatSymbols.getInstance(locale)
        if (rounded.signum() == 0) return DecimalFormat("0", symbols).format(0)
        val magnitude = rounded.abs()
        val pattern =
            if (magnitude >= LARGE || magnitude < SMALL) "0.###########E0"
            else "#,##0.##################"
        return DecimalFormat(pattern, symbols).format(rounded)
    }
}
