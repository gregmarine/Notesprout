package com.notesprout.android.data.tasks

import com.notesprout.android.data.events.ReminderUnit
import com.notesprout.android.data.index.TaskEntity

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

/**
 * The look-ahead reminder on a task: how many days before its due date it starts appearing in the
 * *Upcoming* section.
 *
 * Like the calendar's reminders this is **not** a notification — no alarms, no receivers, nothing
 * that interrupts. It only decides when a task becomes visible in a list the user chooses to open.
 * Weeks are stored distinct from days purely so the editor can say "1 week" rather than "7 days";
 * [leadDays] collapses both for the window math.
 */
object TaskReminders {

    /** The reminder's lead time in whole days, or null when the task has no reminder. */
    fun leadDays(task: TaskEntity): Int? {
        val amount = task.remindAmount?.takeIf { it >= 1 } ?: return null
        return amount * if (unitOf(task.remindUnit) == ReminderUnit.WEEKS) 7 else 1
    }

    /** Editor-facing label, e.g. "1 week before" / "3 days before". Null when there is no reminder. */
    fun label(task: TaskEntity): String? {
        val amount = task.remindAmount?.takeIf { it >= 1 } ?: return null
        val unit = unitOf(task.remindUnit)
        return "$amount ${if (amount == 1) unit.label else unit.labelPlural} before"
    }

    /** Safe parse of a stored unit name; unknown / legacy values read as [ReminderUnit.DAYS]. */
    fun unitOf(name: String?): ReminderUnit =
        ReminderUnit.entries.firstOrNull { it.name == name } ?: ReminderUnit.DAYS
}
