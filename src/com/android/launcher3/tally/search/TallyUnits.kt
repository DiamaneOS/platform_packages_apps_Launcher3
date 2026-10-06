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
import java.util.Locale

/**
 * Unit conversions for Home's search, worked out on the phone: "5 km in mi", "100 °F to C", "2.5 l
 * = cup". Length, mass, volume (US customary cups, pints, quarts, gallons and fluid ounces),
 * temperature, speed, area, data (kB = 1000 B, KiB = 1024 B) and time spans. Units are read by
 * their symbols and English names; the words between them are `in`, `to`, `as`, `into`, `=` or an
 * arrow.
 */
object TallyUnits {
    /** The longest query read. */
    const val MAX_LENGTH = 80

    enum class Kind {
        LENGTH,
        MASS,
        VOLUME,
        TEMPERATURE,
        SPEED,
        AREA,
        DATA,
        TIME,
    }

    /**
     * A unit: its [symbol] for display, and [factor] times a value in it gives the value in its
     * kind's base unit (for temperature, an offset follows: see [toBase]).
     */
    class Unit(val kind: Kind, val symbol: String, val factor: Double, val offset: Double = 0.0) {
        fun toBase(value: Double): Double = (value + offset) * factor

        fun fromBase(value: Double): Double = value / factor - offset
    }

    /** A conversion: [value] in [from] is [result] in [to]. */
    data class Conversion(val value: BigDecimal, val from: Unit, val result: Double, val to: Unit)

    private val CONNECTORS = setOf("in", "to", "as", "into", "=", "->", "→", "⇒")

    private val units: Map<String, Unit> = buildMap {
        fun add(unit: Unit, vararg names: String) {
            put(unit.symbol.lowercase(Locale.ROOT), unit)
            for (name in names) put(name.lowercase(Locale.ROOT), unit)
        }
        // Length, in metres.
        add(
            Unit(Kind.LENGTH, "mm", 0.001),
            "millimeter",
            "millimeters",
            "millimetre",
            "millimetres",
        )
        add(Unit(Kind.LENGTH, "cm", 0.01), "centimeter", "centimeters", "centimetre", "centimetres")
        add(Unit(Kind.LENGTH, "m", 1.0), "meter", "meters", "metre", "metres")
        add(Unit(Kind.LENGTH, "km", 1000.0), "kilometer", "kilometers", "kilometre", "kilometres")
        add(Unit(Kind.LENGTH, "in", 0.0254), "inch", "inches", "\"", "″")
        add(Unit(Kind.LENGTH, "ft", 0.3048), "foot", "feet", "'", "′")
        add(Unit(Kind.LENGTH, "yd", 0.9144), "yard", "yards")
        add(Unit(Kind.LENGTH, "mi", 1609.344), "mile", "miles")
        add(Unit(Kind.LENGTH, "nmi", 1852.0), "nautical mile", "nautical miles")
        // Mass, in kilograms.
        add(Unit(Kind.MASS, "mg", 1e-6), "milligram", "milligrams")
        add(Unit(Kind.MASS, "g", 0.001), "gram", "grams")
        add(Unit(Kind.MASS, "kg", 1.0), "kilogram", "kilograms", "kilo", "kilos")
        add(Unit(Kind.MASS, "t", 1000.0), "tonne", "tonnes")
        add(Unit(Kind.MASS, "oz", 0.028349523125), "ounce", "ounces")
        add(Unit(Kind.MASS, "lb", 0.45359237), "lbs", "pound", "pounds")
        add(Unit(Kind.MASS, "st", 6.35029318), "stone", "stones")
        // Volume, in litres.
        add(
            Unit(Kind.VOLUME, "ml", 0.001),
            "milliliter",
            "milliliters",
            "millilitre",
            "millilitres",
        )
        add(Unit(Kind.VOLUME, "cl", 0.01), "centiliter", "centiliters", "centilitre", "centilitres")
        add(Unit(Kind.VOLUME, "dl", 0.1), "deciliter", "deciliters", "decilitre", "decilitres")
        add(Unit(Kind.VOLUME, "l", 1.0), "liter", "liters", "litre", "litres")
        add(Unit(Kind.VOLUME, "m³", 1000.0), "m3", "cubic meter", "cubic meters", "cubic metre")
        add(Unit(Kind.VOLUME, "tsp", 0.00492892159375), "teaspoon", "teaspoons")
        add(Unit(Kind.VOLUME, "tbsp", 0.01478676478125), "tablespoon", "tablespoons")
        add(Unit(Kind.VOLUME, "fl oz", 0.0295735295625), "floz", "fluid ounce", "fluid ounces")
        add(Unit(Kind.VOLUME, "cup", 0.2365882365), "cups")
        add(Unit(Kind.VOLUME, "pt", 0.473176473), "pint", "pints")
        add(Unit(Kind.VOLUME, "qt", 0.946352946), "quart", "quarts")
        add(Unit(Kind.VOLUME, "gal", 3.785411784), "gallon", "gallons")
        // Temperature, in kelvins.
        add(Unit(Kind.TEMPERATURE, "°C", 1.0, 273.15), "c", "celsius", "degc", "degrees celsius")
        add(
            Unit(Kind.TEMPERATURE, "°F", 5.0 / 9.0, 459.67),
            "f",
            "fahrenheit",
            "degf",
            "degrees fahrenheit",
        )
        add(Unit(Kind.TEMPERATURE, "K", 1.0), "kelvin", "kelvins")
        // Speed, in metres per second.
        add(Unit(Kind.SPEED, "m/s", 1.0), "mps")
        add(Unit(Kind.SPEED, "km/h", 1 / 3.6), "kmh", "kph", "kmph")
        add(Unit(Kind.SPEED, "mph", 0.44704), "mi/h")
        add(Unit(Kind.SPEED, "kn", 1852.0 / 3600.0), "knot", "knots", "kt")
        add(Unit(Kind.SPEED, "ft/s", 0.3048), "fps")
        // Area, in square metres.
        add(Unit(Kind.AREA, "m²", 1.0), "m2", "sq m", "square meter", "square meters", "sqm")
        add(Unit(Kind.AREA, "km²", 1e6), "km2", "sq km", "square kilometer", "square kilometers")
        add(Unit(Kind.AREA, "ha", 1e4), "hectare", "hectares")
        add(Unit(Kind.AREA, "ac", 4046.8564224), "acre", "acres")
        add(Unit(Kind.AREA, "ft²", 0.09290304), "ft2", "sq ft", "square foot", "square feet")
        add(Unit(Kind.AREA, "mi²", 2589988.110336), "mi2", "sq mi", "square mile", "square miles")
        // Data, in bytes.
        add(Unit(Kind.DATA, "B", 1.0), "byte", "bytes")
        add(Unit(Kind.DATA, "kB", 1e3), "kilobyte", "kilobytes")
        add(Unit(Kind.DATA, "MB", 1e6), "megabyte", "megabytes")
        add(Unit(Kind.DATA, "GB", 1e9), "gigabyte", "gigabytes")
        add(Unit(Kind.DATA, "TB", 1e12), "terabyte", "terabytes")
        add(Unit(Kind.DATA, "KiB", 1024.0), "kibibyte", "kibibytes")
        add(Unit(Kind.DATA, "MiB", 1048576.0), "mebibyte", "mebibytes")
        add(Unit(Kind.DATA, "GiB", 1073741824.0), "gibibyte", "gibibytes")
        add(Unit(Kind.DATA, "TiB", 1099511627776.0), "tebibyte", "tebibytes")
        // Time spans, in seconds.
        add(Unit(Kind.TIME, "ms", 0.001), "millisecond", "milliseconds")
        add(Unit(Kind.TIME, "s", 1.0), "sec", "secs", "second", "seconds")
        add(Unit(Kind.TIME, "min", 60.0), "mins", "minute", "minutes")
        add(Unit(Kind.TIME, "h", 3600.0), "hr", "hrs", "hour", "hours")
        add(Unit(Kind.TIME, "d", 86400.0), "day", "days")
        add(Unit(Kind.TIME, "wk", 604800.0), "week", "weeks")
    }

    /** The unit named [name] (a symbol or an English name, any case), or null. */
    @JvmStatic
    fun unit(name: String): Unit? =
        units[name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)]
            ?: units[name.trim().replace(" ", "").lowercase(Locale.ROOT)]

    /**
     * The conversion [text] asks for ("5 km in mi"), or null: a number, a unit, a connecting word
     * and a unit of the same kind (not the same unit).
     */
    @JvmStatic
    @JvmOverloads
    fun convert(text: String, decimalSeparator: Char = '.'): Conversion? {
        if (text.length > MAX_LENGTH) return null
        val spaced =
            text
                .trim()
                .replace("->", " -> ")
                .replace("→", " → ")
                .replace("⇒", " ⇒ ")
                .replace("=", " = ")
        val words = spaced.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return null
        // The connecting word may also be a unit ("5 in in cm"): try each place for it.
        for (i in 1 until words.size - 1) {
            if (words[i].lowercase(Locale.ROOT) !in CONNECTORS) continue
            val source = readAmount(words.subList(0, i).joinToString(" "), decimalSeparator)
            val to = unit(words.subList(i + 1, words.size).joinToString(" "))
            if (source == null || to == null) continue
            val (value, from) = source
            if (from.kind != to.kind || from === to) continue
            val result = to.fromBase(from.toBase(value.toDouble()))
            if (result.isNaN() || result.isInfinite()) continue
            return Conversion(value, from, result, to)
        }
        return null
    }

    /** "5 km", "5km", "-40°F": the number and its unit, or null. */
    private fun readAmount(text: String, decimalSeparator: Char): Pair<BigDecimal, Unit>? {
        val match = AMOUNT.matchEntire(text.trim()) ?: return null
        val number = match.groupValues[1].replace('−', '-').replace(decimalSeparator, '.')
        val value =
            try {
                BigDecimal(number)
            } catch (e: NumberFormatException) {
                return null
            }
        val unit = unit(match.groupValues[2]) ?: return null
        return value to unit
    }

    private val AMOUNT = Regex("([-−]?[0-9]+(?:[.,][0-9]+)?)\\s*(\\D.*)")
}
