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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.tally.search.TallyAnswers.Kind
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Home search's quick answers: sums, conversions and times, all on the phone. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyAnswersTest {

    // Wednesday 7 October 2026, 10:00 in Berlin (summer time, UTC+2).
    private val berlinMorning =
        ZonedDateTime.of(2026, 10, 7, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
    private val english = TallyAnswers.Context(Locale.UK, berlinMorning)
    private val german = TallyAnswers.Context(Locale.GERMANY, berlinMorning)

    private fun sum(text: String, separator: Char = '.') =
        TallyArithmetic.evaluate(text, separator)?.stripTrailingZeros()?.toPlainString()

    @Test
    fun sums() {
        assertEquals("14", sum("2 + 3 * 4"))
        assertEquals("20", sum("(2 + 3) * 4"))
        assertEquals("20", sum("(2+3)(4)"))
        assertEquals("48", sum("12 × 4"))
        assertEquals("48", sum("12x4"))
        assertEquals("3", sum("12 ÷ 4"))
        assertEquals("-1", sum("2 − 3"))
        assertEquals("512", sum("2^3^2"))
        assertEquals("-4", sum("-2^2"))
        assertEquals("0.25", sum("2^-2"))
        assertEquals("1.5", sum("3/2"))
        assertEquals("4", sum("1,5 + 2,5", ','))
    }

    @Test
    fun percentages() {
        assertEquals("220", sum("200 + 10%"))
        assertEquals("180", sum("200 - 10%"))
        assertEquals("30", sum("200 * 15%"))
        assertEquals("0.5", sum("50%"))
    }

    @Test
    fun notSums() {
        assertNull(sum("42")) // a lone number
        assertNull(sum("-5")) // only a sign
        assertNull(sum("1/0"))
        assertNull(sum("2 +"))
        assertNull(sum("(2 + 3"))
        assertNull(sum("battery"))
        assertNull(sum("1,5 + 2", '.')) // a comma is no decimal separator in English
        assertNull(sum("1.2.3 + 1"))
        assertNull(sum("10:30")) // a time, not a division
        assertNull(sum("9".repeat(10) + "^999^999")) // far too large
        assertNull(sum("(".repeat(40) + "1+1" + ")".repeat(40))) // nested too deep
        assertNull(sum("1+".repeat(70) + "1")) // too long
    }

    @Test
    fun sumAnswers() {
        val answer = TallyAnswers.answer("1/3", english)!!
        assertEquals(Kind.SUM, answer.kind)
        assertEquals("0.333333333333", answer.value)
        assertEquals("1/3 =", answer.detail)
        assertEquals("1,234,567.5", TallyAnswers.answer("1234567 + 0.5", english)!!.value)
        assertEquals("1.234.567,5", TallyAnswers.answer("1234567 + 0,5", german)!!.value)
        assertEquals("1E20", TallyAnswers.answer("10^20", english)!!.value)
    }

    @Test
    fun conversions() {
        assertEquals("3.10686 mi", TallyAnswers.answer("5 km in mi", english)!!.value)
        assertEquals("5 km", TallyAnswers.answer("5 km in mi", english)!!.detail)
        assertEquals("37.7778 °C", TallyAnswers.answer("100°F to C", english)!!.value)
        assertEquals("-40 °F", TallyAnswers.answer("-40 celsius in fahrenheit", english)!!.value)
        assertEquals("12.7 cm", TallyAnswers.answer("5 in in cm", english)!!.value)
        assertEquals("60 in", TallyAnswers.answer("5 ft in in", english)!!.value)
        assertEquals("2.20462 lb", TallyAnswers.answer("1 kg = lb", english)!!.value)
        assertEquals("1.024 kB", TallyAnswers.answer("1 KiB to kB", english)!!.value)
        assertEquals("90 min", TallyAnswers.answer("1,5 h in min", german)!!.value)
        assertEquals(Kind.CONVERSION, TallyAnswers.answer("2 cups in ml", english)!!.kind)
    }

    @Test
    fun notConversions() {
        assertNull(TallyUnits.convert("5 km in kg")) // different kinds
        assertNull(TallyUnits.convert("5 km in km")) // the same unit
        assertNull(TallyUnits.convert("5 km"))
        assertNull(TallyUnits.convert("km in mi"))
        assertNull(TallyUnits.convert("5 parsecs in km"))
    }

    @Test
    fun timesElsewhere() {
        val now = TallyAnswers.answer("time in Tokyo", english)!!
        assertEquals(Kind.TIME, now.kind)
        assertEquals("17:00", now.value)
        assertEquals("Tokyo · Wed 7 Oct", now.detail)
        assertEquals("17:00", TallyAnswers.answer("tokyo time", english)!!.value)
        // When it is 15:30 here (Berlin), in New York.
        assertEquals("09:30", TallyAnswers.answer("15:30 in New York", english)!!.value)
        // 9am in London, in Sydney (UTC+11 in October).
        val sydney = TallyAnswers.answer("9am London to Sydney", english)!!
        assertEquals("19:00", sydney.value)
        assertEquals("Sydney · Wed 7 Oct", sydney.detail)
        assertEquals("08:00", TallyAnswers.answer("10:00 in UTC", english)!!.value)
        assertEquals("10:00", TallyAnswers.answer("8:00 UTC to UTC+2", english)!!.value)
        assertEquals("05:00", TallyAnswers.answer("time in São Paulo", english)!!.value)
        assertEquals("17:00", TallyAnswers.answer("10:00 cet in jst", english)!!.value)
    }

    @Test
    fun notTimes() {
        assertNull(TallyZoneTimes.answer("10 in tokyo", berlinMorning)) // no minutes or am/pm
        assertNull(TallyZoneTimes.answer("screen time", berlinMorning))
        assertNull(TallyZoneTimes.answer("time in atlantis", berlinMorning))
        assertNull(TallyZoneTimes.answer("25:00 in tokyo", berlinMorning))
        assertNull(TallyZoneTimes.answer("13pm in tokyo", berlinMorning))
        assertNull(TallyZoneTimes.answer("10:00 utc+19 to tokyo", berlinMorning))
    }

    @Test
    fun noAnswer() {
        assertNull(TallyAnswers.answer("", english))
        assertNull(TallyAnswers.answer("batt", english))
        assertNull(TallyAnswers.answer("2", english))
    }

    @Test
    fun formatting() {
        assertEquals("0", TallyAnswers.format(BigDecimal("0.000"), Locale.UK, 12))
        assertEquals("1.5E-7", TallyAnswers.format(BigDecimal("0.00000015"), Locale.UK, 12))
        assertEquals("-2.5", TallyAnswers.format(BigDecimal("-2.50"), Locale.UK, 12))
    }
}
