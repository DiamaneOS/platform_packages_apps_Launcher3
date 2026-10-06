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
import kotlin.math.abs
import kotlin.math.pow

/**
 * Sums for Home's search, worked out on the phone: a small recursive-descent parser over numbers,
 * the four operations, powers, percentages and brackets. No script engine, and nothing evaluated
 * but these; input is capped at [MAX_LENGTH] characters and [MAX_DEPTH] levels of nesting.
 *
 * Multiplication takes `*`, `×`, `·` and `x`; division `/` and `÷` (not `:`, which a time has);
 * minus `-` and `−`; powers `^`. A number takes `.` or the language's own decimal separator,
 * without grouping. `a ± b%` adds or takes b percent of a, as a calculator does; elsewhere `b%` is
 * b / 100. `2(3)` multiplies.
 */
object TallyArithmetic {
    /** The longest sum read. */
    const val MAX_LENGTH = 120
    /** The deepest nesting of brackets, signs and powers read. */
    private const val MAX_DEPTH = 32
    /** Exact powers up to this exponent; others in double precision. */
    private const val MAX_EXACT_EXPONENT = 999
    /** Results beyond this magnitude are not shown. */
    private val LIMIT = BigDecimal("1E1000")
    private val HUNDRED = BigDecimal(100)
    private val MC = MathContext.DECIMAL128

    /**
     * The value of [text] when it is a whole sum with at least one operation between two values (so
     * a lone number is not one), else null: unreadable, too long, dividing by zero, or too large.
     */
    @JvmStatic
    @JvmOverloads
    fun evaluate(text: String, decimalSeparator: Char = '.'): BigDecimal? {
        if (text.length > MAX_LENGTH || text.none { it.isDigit() }) return null
        val parser = Parser(text, decimalSeparator)
        return try {
            val value = parser.parseAll()
            if (!parser.sawOperation || value.abs() > LIMIT) null else value
        } catch (e: SyntaxError) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    private class SyntaxError : Exception()

    /** A value, and whether it is a bare percentage (`b%`), which `a ± b%` reads as of a. */
    private class Value(val number: BigDecimal, val percent: Boolean = false)

    private class Parser(private val text: String, private val decimalSeparator: Char) {
        private var pos = 0
        private var depth = 0
        var sawOperation = false
            private set

        fun parseAll(): BigDecimal {
            val value = expression().number
            skipSpaces()
            if (pos != text.length) throw SyntaxError()
            return value
        }

        // expression := term (('+' | '-') term)*
        private fun expression(): Value {
            enter()
            var left = term()
            while (true) {
                skipSpaces()
                val op = peek()
                val sign =
                    when (op) {
                        '+' -> 1
                        '-',
                        '−' -> -1
                        else -> break
                    }
                pos++
                val right = term()
                sawOperation = true
                val amount =
                    if (right.percent) left.number.multiply(right.number, MC) else right.number
                left =
                    Value(
                        if (sign > 0) left.number.add(amount, MC)
                        else left.number.subtract(amount, MC)
                    )
            }
            leave()
            return left
        }

        // term := factor (('*' | '/' | implicit before '(') factor)*
        private fun term(): Value {
            var left = unary()
            while (true) {
                skipSpaces()
                val op = peek()
                val multiply =
                    when (op) {
                        '*',
                        '×',
                        '·',
                        'x',
                        'X' -> true
                        '/',
                        '÷' -> false
                        '(' -> true
                        else -> break
                    }
                if (op != '(') pos++
                val right = unary()
                sawOperation = true
                left =
                    Value(
                        if (multiply) left.number.multiply(right.number, MC)
                        else {
                            if (right.number.signum() == 0) throw ArithmeticException()
                            left.number.divide(right.number, MC)
                        }
                    )
            }
            return left
        }

        // unary := ('+' | '-') unary | power
        private fun unary(): Value {
            skipSpaces()
            return when (peek()) {
                '-',
                '−' -> {
                    pos++
                    enter()
                    val inner = unary()
                    leave()
                    Value(inner.number.negate(), inner.percent)
                }
                '+' -> {
                    pos++
                    enter()
                    val inner = unary()
                    leave()
                    inner
                }
                else -> power()
            }
        }

        // power := postfix ('^' unary)?   (right-associative: 2^3^2 is 2^9)
        private fun power(): Value {
            val base = postfix()
            skipSpaces()
            if (peek() != '^') return base
            pos++
            enter()
            val exponent = unary()
            leave()
            sawOperation = true
            return Value(pow(base.number, exponent.number))
        }

        // postfix := primary '%'*
        private fun postfix(): Value {
            var value = primary()
            var percent = false
            while (true) {
                skipSpaces()
                if (peek() != '%') break
                pos++
                sawOperation = true
                value = value.divide(HUNDRED, MC)
                percent = true
            }
            return Value(value, percent)
        }

        // primary := number | '(' expression ')'
        private fun primary(): BigDecimal {
            skipSpaces()
            if (peek() == '(') {
                pos++
                val inner = expression()
                skipSpaces()
                if (peek() != ')') throw SyntaxError()
                pos++
                return inner.number
            }
            return number()
        }

        private fun number(): BigDecimal {
            val start = pos
            val digits = StringBuilder()
            var separators = 0
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c in '0'..'9' -> digits.append(c)
                    c == '.' || c == decimalSeparator -> {
                        separators++
                        digits.append('.')
                    }
                    else -> break
                }
                pos++
            }
            if (pos == start || separators > 1 || digits.none { it.isDigit() }) {
                throw SyntaxError()
            }
            return BigDecimal(digits.toString())
        }

        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole =
                try {
                    exponent.stripTrailingZeros().intValueExact()
                } catch (e: ArithmeticException) {
                    null
                }
            if (whole != null && abs(whole) <= MAX_EXACT_EXPONENT) {
                if (whole >= 0) return base.pow(whole, MC)
                if (base.signum() == 0) throw ArithmeticException()
                return BigDecimal.ONE.divide(base.pow(-whole, MC), MC)
            }
            val result = base.toDouble().pow(exponent.toDouble())
            if (result.isNaN() || result.isInfinite()) throw ArithmeticException()
            return BigDecimal(result, MC)
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun enter() {
            if (++depth > MAX_DEPTH) throw SyntaxError()
        }

        private fun leave() {
            depth--
        }
    }

    /** [value] rounded to [digits] significant digits. */
    internal fun round(value: BigDecimal, digits: Int): BigDecimal =
        value.round(MathContext(digits, RoundingMode.HALF_EVEN))
}
