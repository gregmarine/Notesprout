package com.notesprout.android.data.tasks

/**
 * Value types for the `tasks` table. Deliberately **plain enums, not `@Serializable`** — the task
 * row is fully columnar, so these are stored by [Enum.name] in a TEXT column and nothing here is
 * ever encoded to JSON.
 */

/** Which kind of row this is. Only [TASK] is ever written today; [ROUTINE] is the reservation. */
enum class TaskRowType {
    TASK,
    ROUTINE;

    companion object {
        const val TASK_NAME = "TASK"
    }
}

/**
 * A task's lifecycle state.
 *
 * [SKIPPED] is not a deletion and not a completion: the row stays as history and, for a recurring
 * task, still advances the series exactly as [DONE] does. It records "I consciously passed on this
 * one", which is the honest answer for a chore the user chose not to do.
 */
enum class TaskState(val label: String) {
    NOT_DONE("Not done"),
    DONE("Done"),
    SKIPPED("Skipped");

    /** True once the task has been acted on — the two states that live in the Done view. */
    val isResolved: Boolean get() = this != NOT_DONE

    companion object {
        /** Safe parse of a stored [name]; unknown / legacy values read as [NOT_DONE]. */
        fun fromName(name: String?): TaskState = entries.firstOrNull { it.name == name } ?: NOT_DONE
    }
}

/**
 * Packing for the weekly weekday set, stored as an integer bitmask so the rule stays columnar.
 *
 * Bit *n* represents ISO day *n + 1* — Mon = bit 0 … Sun = bit 6 — matching
 * [java.time.DayOfWeek.getValue] and the ISO convention `RecurrenceRule.weekdays` already uses.
 * A mask of 0 (or a null column) means "the anchor's own weekday", the same default the recurrence
 * engine applies for an empty weekday list.
 */
object TaskWeekdays {

    /** ISO days (1 = Mon … 7 = Sun) → bitmask. Values outside 1..7 are ignored. */
    fun pack(isoDays: Collection<Int>): Int =
        isoDays.filter { it in 1..7 }.fold(0) { acc, d -> acc or (1 shl (d - 1)) }

    /** Bitmask → sorted ISO days. A null or empty mask yields an empty list ("anchor's weekday"). */
    fun unpack(mask: Int?): List<Int> {
        val m = mask ?: return emptyList()
        return (1..7).filter { m and (1 shl (it - 1)) != 0 }
    }
}
