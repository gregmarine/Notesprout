package com.notesprout.android.data

import com.notesprout.android.data.events.ReminderUnit
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.TaskReminders
import com.notesprout.android.data.tasks.TaskRowType
import com.notesprout.android.data.tasks.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Which section an open task lands in, and — the part that carries real consequences — when a
 * future-dated task is shown **at all**.
 *
 * *Upcoming* is gated on the task's look-ahead reminder, mirroring calendar events: a task surfaces
 * from `due − lead` onwards, and one with no reminder never surfaces there. Because the list is the
 * only view a task has (there is no grid to fall back on), that means a dated task with no reminder
 * is invisible until its due date. Deliberate, and pinned here so it cannot drift silently.
 */
class TaskSectioningTest {

    private val today = LocalDate.of(2026, 7, 25)
    private val todayDay = today.toEpochDay()

    private fun task(
        due: LocalDate? = null,
        remindAmount: Int? = null,
        remindUnit: ReminderUnit = ReminderUnit.DAYS,
    ) = TaskEntity(
        id = "t",
        type = TaskRowType.TASK_NAME,
        title = "t",
        state = TaskState.NOT_DONE.name,
        dueEpochDay = due?.toEpochDay(),
        remindAmount = remindAmount,
        remindUnit = remindUnit.name,
        createdAt = 0,
        updatedAt = 0,
    )

    // ── Always visible ─────────────────────────────────────────────────────────

    @Test
    fun `an undated task is always in No date, reminder or not`() {
        assertEquals(TaskSectionKind.NO_DATE, sectionFor(task(), todayDay))
    }

    @Test
    fun `a task due today is in Today even with no reminder`() {
        assertEquals(TaskSectionKind.TODAY, sectionFor(task(due = today), todayDay))
    }

    @Test
    fun `an overdue task is always shown even with no reminder`() {
        val t = task(due = today.minusDays(30))
        assertEquals(TaskSectionKind.OVERDUE, sectionFor(t, todayDay))
    }

    // ── The reminder window ────────────────────────────────────────────────────

    @Test
    fun `a future task with no reminder is not shown at all`() {
        assertNull(sectionFor(task(due = today.plusDays(3)), todayDay))
    }

    @Test
    fun `a future task appears once its reminder window opens`() {
        // Due in 7 days with a 7-day lead: today is exactly the first day of the window.
        val t = task(due = today.plusDays(7), remindAmount = 7)
        assertEquals(TaskSectionKind.UPCOMING, sectionFor(t, todayDay))
    }

    @Test
    fun `a future task stays hidden until its window opens`() {
        // Due in 8 days with a 7-day lead: one day too early.
        val t = task(due = today.plusDays(8), remindAmount = 7)
        assertNull(sectionFor(t, todayDay))
        // …and it appears tomorrow.
        assertEquals(TaskSectionKind.UPCOMING, sectionFor(t, todayDay + 1))
    }

    @Test
    fun `a task stays visible on every day of its window, not just the first`() {
        val t = task(due = today.plusDays(10), remindAmount = 2, remindUnit = ReminderUnit.WEEKS)
        for (offset in 0..9) {
            assertEquals(
                "day +$offset should be in Upcoming",
                TaskSectionKind.UPCOMING,
                sectionFor(t, todayDay + offset),
            )
        }
        // On the due day itself it graduates to Today.
        assertEquals(TaskSectionKind.TODAY, sectionFor(t, todayDay + 10))
    }

    @Test
    fun `weeks are seven days`() {
        val t = task(due = today.plusDays(14), remindAmount = 2, remindUnit = ReminderUnit.WEEKS)
        assertEquals(TaskSectionKind.UPCOMING, sectionFor(t, todayDay))
        val tooEarly = task(due = today.plusDays(15), remindAmount = 2, remindUnit = ReminderUnit.WEEKS)
        assertNull(sectionFor(tooEarly, todayDay))
    }

    @Test
    fun `a zero or negative reminder amount counts as no reminder`() {
        assertNull(sectionFor(task(due = today.plusDays(1), remindAmount = 0), todayDay))
        assertNull(sectionFor(task(due = today.plusDays(1), remindAmount = -3), todayDay))
    }

    // ── The All view (ungated) ─────────────────────────────────────────────────

    @Test
    fun `ungated, a future task with no reminder is still reachable in Upcoming`() {
        val t = task(due = today.plusDays(3))
        assertNull(sectionFor(t, todayDay))
        assertEquals(TaskSectionKind.UPCOMING, sectionFor(t, todayDay, gated = false))
    }

    @Test
    fun `ungated, a task whose window has not opened is still reachable`() {
        val t = task(due = today.plusDays(90), remindAmount = 1)
        assertNull(sectionFor(t, todayDay))
        assertEquals(TaskSectionKind.UPCOMING, sectionFor(t, todayDay, gated = false))
    }

    @Test
    fun `ungated never hides anything - every open task lands in a section`() {
        val everyShape = listOf(
            task(),
            task(due = today),
            task(due = today.minusDays(5)),
            task(due = today.plusDays(1)),
            task(due = today.plusDays(365)),
            task(due = today.plusDays(365), remindAmount = 2, remindUnit = ReminderUnit.WEEKS),
        )
        for (t in everyShape) {
            assertNotNull(
                "due=${t.dueEpochDay} remind=${t.remindAmount} should be reachable in All",
                sectionFor(t, todayDay, gated = false),
            )
        }
    }

    // ── "Hidden until" note ────────────────────────────────────────────────────

    @Test
    fun `visibleFrom is the due day less the lead`() {
        val t = task(due = today.plusDays(30), remindAmount = 1, remindUnit = ReminderUnit.WEEKS)
        assertEquals(today.plusDays(23).toEpochDay(), visibleFrom(t))
    }

    @Test
    fun `visibleFrom with no reminder is the due day itself`() {
        val t = task(due = today.plusDays(30))
        assertEquals(today.plusDays(30).toEpochDay(), visibleFrom(t))
    }

    @Test
    fun `visibleFrom is null for an undated task, which is never hidden`() {
        assertNull(visibleFrom(task()))
    }

    // ── Lead-time helpers ──────────────────────────────────────────────────────

    @Test
    fun `leadDays collapses weeks and rejects meaningless amounts`() {
        assertEquals(3, TaskReminders.leadDays(task(remindAmount = 3)))
        assertEquals(14, TaskReminders.leadDays(task(remindAmount = 2, remindUnit = ReminderUnit.WEEKS)))
        assertNull(TaskReminders.leadDays(task()))
        assertNull(TaskReminders.leadDays(task(remindAmount = 0)))
    }

    @Test
    fun `reminder label singularizes`() {
        assertEquals("1 day before", TaskReminders.label(task(remindAmount = 1)))
        assertEquals("3 days before", TaskReminders.label(task(remindAmount = 3)))
        assertEquals(
            "1 week before",
            TaskReminders.label(task(remindAmount = 1, remindUnit = ReminderUnit.WEEKS)),
        )
        assertNull(TaskReminders.label(task()))
    }

    @Test
    fun `an unknown stored unit reads as days rather than throwing`() {
        val t = task(remindAmount = 5).copy(remindUnit = "FORTNIGHTS")
        assertEquals(5, TaskReminders.leadDays(t))
    }
}
