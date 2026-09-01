package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The tag manager's tables in the host's extension store (arc 22 / X3) — declared once, applied by
 * the host. Every statement is validated by `StoreSql.checkDdl` at construction, so a mistake here
 * fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * tag        (id, display, identityKey UNIQUE, createdAt)
 * assignment (tagId → tag.id ON DELETE CASCADE, notebookId, pageId, createdAt)
 * ```
 *
 * Three things in that shape are decisions, not spelling:
 *
 *  - **`identityKey` is a stored, uniquely indexed column.** Arc 21 derived it on every read and
 *    refused to store it, because a second copy of an answer can disagree with the question. On rows
 *    the reasoning inverts: the uniqueness of a tag identity has to be enforced by *something*, and
 *    a `UNIQUE` index is the only thing that can enforce it across two processes with no lock. One
 *    function still writes it ([com.symmetricalpalmtree.notesproutsn.extension.TagRules.identityKey])
 *    and `TagRecord` re-derives it, so the two cannot drift silently.
 *  - **`pageId` is `''`, never NULL, and it is in the primary key.** In SQL `NULL` is not equal to
 *    `NULL`, so a nullable page column would let the same notebook tag be inserted twice and the
 *    primary key would say nothing. `''` is a value; the `String?` ⇄ `""` mapping happens once, at
 *    [TagStore]'s door.
 *  - **The cascade is real.** Foreign keys are ON for the store connection, so deleting a tag row
 *    takes every assignment of it — the whole "delete with blast radius" is one statement, and there
 *    is no window in which an assignment names a tag that is gone.
 *
 * `assignment_target` indexes `(notebookId, pageId)` because that is the screen's read: the tags on
 * one notebook and on each of its pages. The `IN (…)` read the host's search merge makes goes the
 * other way and rides the primary key's leading `tagId`.
 */
object TagSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                """CREATE TABLE tag (
                       id TEXT PRIMARY KEY,
                       display TEXT NOT NULL,
                       identityKey TEXT NOT NULL UNIQUE,
                       createdAt INTEGER NOT NULL);""",
                """CREATE TABLE assignment (
                       tagId TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
                       notebookId TEXT NOT NULL,
                       pageId TEXT NOT NULL DEFAULT '',
                       createdAt INTEGER NOT NULL,
                       PRIMARY KEY (tagId, notebookId, pageId));""",
                "CREATE INDEX assignment_target ON assignment(notebookId, pageId);",
            ),
        ),
    )
}
