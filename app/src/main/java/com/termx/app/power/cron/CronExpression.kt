package com.termx.app.power.cron

import android.util.Log
import java.util.*

/**
 * Parses and matches standard 5-field cron expressions.
 *
 * Crontab format:
 *   ┌──────── minute     (0-59)
 *   │ ┌────── hour       (0-23)
 *   │ │ ┌──── dayOfMonth (1-31)
 *   │ │ │ ┌── month      (1-12)
 *   │ │ │ │ ┌ dayOfWeek  (0-6, 0=Sunday)
 *   │ │ │ │ │
 *   * * * * * command
 *
 * Supported field syntax:
 *   *         Wildcard – matches every value
 *   5         Literal – matches exactly 5
 *   1-5       Range – matches 1,2,3,4,5
 *   * /10      Step – every 10th value starting from the field minimum
 *   1-15/3    Range with step – 1,4,7,10,13
 *   1,3,5     List – matches 1, 3, or 5
 *   1-5,10    Mixed – range and literal combined
 *
 * Special shorthand strings:
 *   @hourly    → 0 * * * *
 *   @daily     → 0 0 * * *
 *   @weekly    → 0 0 * * 0
 *   @monthly   → 0 0 1 * *
 *   @yearly    → 0 0 1 1 *
 *   @reboot    → Run once on boot (no cron fields)
 *   @every_Nm  → Run every N minutes (interval-based)
 *
 * Day-of-week aliases:
 *   sun=0 mon=1 tue=2 wed=3 thu=4 fri=5 sat=6
 *
 * Month aliases:
 *   jan=1 feb=2 mar=3 apr=4 may=5 jun=6
 *   jul=7 aug=8 sep=9 oct=10 nov=11 dec=12
 */
class CronExpression private constructor(
    val rawExpression: String,
    val minute: CronField,
    val hour: CronField,
    val dayOfMonth: CronField,
    val month: CronField,
    val dayOfWeek: CronField,
    val isReboot: Boolean,
    val intervalMinutes: Int
) {

    companion object {
        private const val TAG = "CronExpression"

        /** Ordered month name → number mapping. */
        private val MONTH_NAMES = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4,
            "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
            "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )

        /** Ordered day-of-week name → number mapping. */
        private val DOW_NAMES = mapOf(
            "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3,
            "thu" to 4, "fri" to 5, "sat" to 6
        )

        /** Field boundary definitions: [name, min, max]. */
        private val FIELD_DEFS = arrayOf(
            arrayOf("minute",     0, 59),
            arrayOf("hour",       0, 23),
            arrayOf("dayOfMonth", 1, 31),
            arrayOf("month",      1, 12),
            arrayOf("dayOfWeek",  0, 6)
        )

        // ── Public factory ──────────────────────────────────────────

        /**
         * Parse a full cron line that may include a special shorthand or
         * the standard 5-field expression. Returns null on parse failure.
         */
        fun parse(expression: String): CronExpression? {
            val trimmed = expression.trim()
            if (trimmed.isEmpty()) {
                Log.w(TAG, "Empty cron expression")
                return null
            }

            // ── Special shorthand strings ──────────────────────────
            val lower = trimmed.lowercase(Locale.ROOT)
            when {
                lower == "@hourly"  -> return fromFields("0 * * * *",  trimmed)
                lower == "@daily"   -> return fromFields("0 0 * * *",  trimmed)
                lower == "@weekly"  -> return fromFields("0 0 * * 0",  trimmed)
                lower == "@monthly" -> return fromFields("0 0 1 * *",  trimmed)
                lower == "@yearly" || lower == "@annually" ->
                    return fromFields("0 0 1 1 *", trimmed)
                lower == "@reboot"  -> return rebootExpression(trimmed)
                lower.startsWith("@every_") -> return parseEveryInterval(trimmed)
            }

            // ── Standard 5-field expression ────────────────────────
            return fromFields(trimmed, trimmed)
        }

        // ── Internal helpers ────────────────────────────────────────

        private fun fromFields(fieldsStr: String, rawExpression: String): CronExpression? {
            val parts = fieldsStr.split(Regex("\\s+"))
            if (parts.size < 5) {
                Log.w(TAG, "Expected at least 5 fields, got ${parts.size} in: $fieldsStr")
                return null
            }

            val fields = arrayOfNulls<CronField>(5)
            for (i in 0 until 5) {
                val (name, min, max) = FIELD_DEFS[i]
                val nameMap = when (i) {
                    3 -> MONTH_NAMES   // month
                    4 -> DOW_NAMES     // day of week
                    else -> emptyMap()
                }
                fields[i] = parseField(parts[i], min as Int, max as Int, nameMap)
                    ?: run {
                        Log.w(TAG, "Failed to parse $name field: '${parts[i]}'")
                        return null
                    }
            }

            return CronExpression(
                rawExpression = rawExpression,
                minute     = fields[0]!!,
                hour       = fields[1]!!,
                dayOfMonth = fields[2]!!,
                month      = fields[3]!!,
                dayOfWeek  = fields[4]!!,
                isReboot   = false,
                intervalMinutes = 0
            )
        }

        private fun rebootExpression(raw: String): CronExpression {
            // @reboot: match-everything fields so matches() always returns true,
            // but the scheduler only runs it once on boot.
            val wildcard = CronField(0, 59, setOf())
            return CronExpression(
                rawExpression = raw,
                minute     = wildcard,
                hour       = CronField(0, 23, setOf()),
                dayOfMonth = CronField(1, 31, setOf()),
                month      = CronField(1, 12, setOf()),
                dayOfWeek  = CronField(0, 6, setOf()),
                isReboot   = true,
                intervalMinutes = 0
            )
        }

        /**
         * Parse @every_Nm syntax (e.g. @every_5m, @every_30m).
         * Creates a wildcard expression that the scheduler treats as interval-based.
         */
        private fun parseEveryInterval(raw: String): CronExpression? {
            val match = Regex("^@every_(\\d+)m?$", RegexOption.IGNORE_CASE)
                .matchEntire(raw)
            if (match == null) {
                Log.w(TAG, "Invalid @every_Nm syntax: $raw")
                return null
            }
            val minutes = match.groupValues[1].toIntOrNull()
            if (minutes == null || minutes <= 0) {
                Log.w(TAG, "Invalid interval in: $raw")
                return null
            }
            val wildcard = CronField(0, 59, setOf())
            return CronExpression(
                rawExpression = raw,
                minute     = wildcard,
                hour       = CronField(0, 23, setOf()),
                dayOfMonth = CronField(1, 31, setOf()),
                month      = CronField(1, 12, setOf()),
                dayOfWeek  = CronField(0, 6, setOf()),
                isReboot   = false,
                intervalMinutes = minutes
            )
        }

        /**
         * Parse a single cron field string into a [CronField].
         *
         * @param token   The raw field string (e.g. "1-5/2", "* /10", "1,3,5")
         * @param min     Minimum valid value for the field
         * @param max     Maximum valid value for the field
         * @param nameMap Optional name-to-number mapping (months, weekdays)
         */
        private fun parseField(
            token: String,
            min: Int,
            max: Int,
            nameMap: Map<String, Int>
        ): CronField? {
            val values = mutableSetOf<Int>()

            // Split on commas to handle lists (e.g. "1,3,5")
            for (part in token.split(",")) {
                val resolved = resolveName(part.trim(), nameMap)

                // Check for step syntax (e.g. "* /5", "1-10/2")
                val stepParts = resolved.split("/")
                val rangeStr = stepParts[0]
                val step = if (stepParts.size > 1) {
                    stepParts[1].toIntOrNull() ?: return null
                } else {
                    1
                }
                if (step <= 0) return null

                // Determine range bounds
                val rangeMin: Int
                val rangeMax: Int
                if (rangeStr == "*") {
                    rangeMin = min
                    rangeMax = max
                } else if (rangeStr.contains("-")) {
                    val bounds = rangeStr.split("-")
                    if (bounds.size != 2) return null
                    rangeMin = resolveName(bounds[0], nameMap).toIntOrNull() ?: return null
                    rangeMax = resolveName(bounds[1], nameMap).toIntOrNull() ?: return null
                } else {
                    rangeMin = rangeStr.toIntOrNull() ?: return null
                    rangeMax = rangeMin
                }

                // Validate bounds
                if (rangeMin < min || rangeMax > max || rangeMin > rangeMax) {
                    Log.w(TAG, "Field value out of range: $rangeMin-$rangeMax (allowed $min-$max)")
                    return null
                }

                // Generate stepped values
                var v = rangeMin
                while (v <= rangeMax) {
                    values.add(v)
                    v += step
                }
            }

            return CronField(min, max, values)
        }

        /** Replace name aliases (mon, jan, etc.) with their numeric values. */
        private fun resolveName(token: String, nameMap: Map<String, Int>): String {
            val lower = token.lowercase(Locale.ROOT)
            return nameMap[lower]?.toString() ?: token
        }
    }

    // ── Matching ────────────────────────────────────────────────────

    /**
     * Check whether this expression matches the given [Calendar].
     * Only examines the five cron fields (minute, hour, day-of-month,
     * month, day-of-week); does not consider seconds or milliseconds.
     */
    fun matches(calendar: Calendar): Boolean {
        if (isReboot) return false // @reboot is never time-matched
        return minute.matches(calendar.get(Calendar.MINUTE)) &&
               hour.matches(calendar.get(Calendar.HOUR_OF_DAY)) &&
               dayOfMonth.matches(calendar.get(Calendar.DAY_OF_MONTH)) &&
               month.matches(calendar.get(Calendar.MONTH) + 1) && // Calendar.MONTH is 0-based
               dayOfWeek.matches(calendar.get(Calendar.DAY_OF_WEEK) - 1) // Calendar: 1=Sun
    }

    // ── Next execution time ─────────────────────────────────────────

    /**
     * Calculate the next execution time after [afterMillis].
     * Uses iterative scanning – fast enough for typical cron schedules.
     * Returns 0 if no matching time can be found within 4 years.
     */
    fun nextExecutionTime(afterMillis: Long): Long {
        if (isReboot) return 0L
        if (intervalMinutes > 0) {
            // For @every_Nm, the scheduler manages intervals directly
            return afterMillis + intervalMinutes * 60_000L
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = afterMillis
            // Start from the next minute (clear seconds & millis)
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Brute-force search – cap at 4 years to prevent infinite loops
        val limit = (afterMillis + 4L * 365 * 24 * 3600 * 1000)
        while (cal.timeInMillis < limit) {
            if (matches(cal)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }

        Log.w(TAG, "No next execution found within 4 years for: $rawExpression")
        return 0L
    }

    // ── Human-readable description ──────────────────────────────────

    /**
     * Generate a human-readable English description of this schedule.
     */
    fun describe(): String {
        if (isReboot) return "Run on boot"
        if (intervalMinutes > 0) return "Run every $intervalMinutes minute(s)"

        val parts = mutableListOf<String>()
        parts.add(describeField("minute", minute, 0, 59))
        parts.add(describeField("hour", hour, 0, 23))
        parts.add(describeField("day of month", dayOfMonth, 1, 31))
        parts.add(describeField("month", month, 1, 12))
        parts.add(describeField("day of week", dayOfWeek, 0, 6))

        return parts.joinToString(", ")
    }

    private fun describeField(name: String, field: CronField, min: Int, max: Int): String {
        if (field.isWildcard(min, max)) return ""
        if (field.values.size == 1) {
            val v = field.values.first()
            val label = when (name) {
                "day of week" -> DOW_NAMES.entries.find { it.value == v }?.key?.replaceFirstChar { it.uppercase() } ?: v.toString()
                "month" -> MONTH_NAMES.entries.find { it.value == v }?.key?.replaceFirstChar { it.uppercase() } ?: v.toString()
                else -> v.toString()
            }
            return "$name: $label"
        }
        return "$name: ${field.values.sorted().joinToString(",")}"
    }

    // ── Validation ──────────────────────────────────────────────────

    /**
     * Validate a cron expression string without fully parsing it.
     * Returns null if valid, or an error message if invalid.
     */
    fun validate(): String? {
        if (isReboot) return null
        if (intervalMinutes > 0) {
            if (intervalMinutes < 1) return "Interval must be at least 1 minute"
            if (intervalMinutes > 525600) return "Interval cannot exceed 525600 minutes (1 year)"
            return null
        }
        // Fields already validated during parsing
        if (minute.values.isEmpty() && !minute.isWildcard(0, 59))
            return "No valid minute values"
        if (hour.values.isEmpty() && !hour.isWildcard(0, 23))
            return "No valid hour values"
        if (dayOfMonth.values.isEmpty() && !dayOfMonth.isWildcard(1, 31))
            return "No valid day-of-month values"
        if (month.values.isEmpty() && !month.isWildcard(1, 12))
            return "No valid month values"
        if (dayOfWeek.values.isEmpty() && !dayOfWeek.isWildcard(0, 6))
            return "No valid day-of-week values"
        return null
    }

    override fun toString(): String = rawExpression

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CronExpression) return false
        return rawExpression == other.rawExpression
    }

    override fun hashCode(): Int = rawExpression.hashCode()
}

/**
 * Represents a single field in a cron expression.
 *
 * @param min    The minimum valid value for this field.
 * @param max    The maximum valid value for this field.
 * @param values The explicitly listed matching values. An empty set is
 *               treated as a wildcard (matches everything in [min..max]).
 */
data class CronField(
    val min: Int,
    val max: Int,
    val values: Set<Int>
) {
    /** Check if [value] is matched by this field. */
    fun matches(value: Int): Boolean {
        // Empty values set → wildcard
        return values.isEmpty() || values.contains(value)
    }

    /** Whether this field matches every value in its range. */
    fun isWildcard(fieldMin: Int, fieldMax: Int): Boolean {
        if (values.isEmpty()) return true
        if (values.size != (fieldMax - fieldMin + 1)) return false
        return (fieldMin..fieldMax).all { values.contains(it) }
    }
}
