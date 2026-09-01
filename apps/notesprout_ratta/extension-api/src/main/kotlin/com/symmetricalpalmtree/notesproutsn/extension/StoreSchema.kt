package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * An extension's declared tables (arc 22 / X1): ordered DDL steps, where `steps[i]` is the DDL
 * that takes a store from version `i` to version `i + 1` — version 1 creates, version 2 alters, and
 * so on. `version == steps.size` always. The host keeps the version it has applied per store,
 * runs only the missing steps (each in its own transaction with the version bump, so a crash
 * between steps resumes rather than repeats), and refuses a downgrade
 * (`ExtensionContract.STORE_SCHEMA_NEWER`): an extension never sees a store at a schema newer than
 * it knows. A step that has landed is never edited — a change is a new step.
 *
 * **Every statement is pre-validated by [StoreSql.checkDdl] at construction**, so a bad schema
 * fails on the extension's side, at the declaration, not at bind — and again at unmarshal on the
 * host's. Allowed: `CREATE TABLE`, `CREATE [UNIQUE] INDEX`, `ALTER TABLE … ADD COLUMN | RENAME …`,
 * `DROP TABLE|INDEX`, every `IF [NOT] EXISTS` form, `WITHOUT ROWID`, `REFERENCES … ON DELETE …`
 * (foreign keys are ON for the store connection, so a declared cascade cascades). No views, no
 * triggers, no virtual tables, no temp objects. Table and index names are [StoreNames]-shaped.
 *
 * Caps: `1..STORE_MAX_SCHEMA_STEPS` steps, `1..STORE_MAX_STEP_STATEMENTS` statements each, at
 * most `STORE_MAX_TABLES` `CREATE TABLE` statements over the whole schema.
 *
 * Wire form: `int version · int stepCount · per step (int count · String…)`. [requireValid] runs
 * at construction, so at unmarshal too.
 */
class StoreSchema(
    val version: Int,
    val steps: List<List<String>>,
) : Parcelable {

    init {
        requireValid(version, steps)
    }

    private constructor(parcel: Parcel) : this(
        version = parcel.readInt(),
        steps = readSteps(parcel),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(version)
        dest.writeInt(steps.size)
        for (step in steps) {
            dest.writeInt(step.size)
            for (sql in step) dest.writeString(sql)
        }
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The constructor's checks, pure so they are JVM-testable. Throws `IllegalArgumentException`
         *  naming the step and statement that failed. */
        fun requireValid(version: Int, steps: List<List<String>>) {
            require(version in 1..ExtensionContract.STORE_MAX_SCHEMA_STEPS) {
                "schema version must be 1..${ExtensionContract.STORE_MAX_SCHEMA_STEPS} ($version)"
            }
            require(steps.size == version) { "version $version but ${steps.size} step(s)" }
            var tables = 0
            for ((i, step) in steps.withIndex()) {
                require(step.size in 1..ExtensionContract.STORE_MAX_STEP_STATEMENTS) {
                    "step ${i + 1} must hold 1..${ExtensionContract.STORE_MAX_STEP_STATEMENTS} statement(s) (${step.size})"
                }
                for ((j, sql) in step.withIndex()) {
                    try {
                        StoreSql.checkDdl(sql)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("step ${i + 1} statement ${j + 1}: ${e.message}", e)
                    }
                    if (StoreSql.createsTable(sql)) tables++
                }
            }
            require(tables <= ExtensionContract.STORE_MAX_TABLES) {
                "schema creates $tables tables — at most ${ExtensionContract.STORE_MAX_TABLES}"
            }
        }

        private fun readSteps(parcel: Parcel): List<List<String>> {
            val stepCount = parcel.readInt()
            require(stepCount in 0..ExtensionContract.STORE_MAX_SCHEMA_STEPS) { "step count $stepCount" }
            val steps = ArrayList<List<String>>(stepCount)
            repeat(stepCount) {
                val n = parcel.readInt()
                require(n in 0..ExtensionContract.STORE_MAX_STEP_STATEMENTS) { "statement count $n" }
                val step = ArrayList<String>(n)
                repeat(n) { step += requireNotNull(parcel.readString()) { "null statement" } }
                steps += step
            }
            return steps
        }

        @JvmField
        val CREATOR: Parcelable.Creator<StoreSchema> = object : Parcelable.Creator<StoreSchema> {
            override fun createFromParcel(parcel: Parcel): StoreSchema = StoreSchema(parcel)
            override fun newArray(size: Int): Array<StoreSchema?> = arrayOfNulls(size)
        }
    }
}
