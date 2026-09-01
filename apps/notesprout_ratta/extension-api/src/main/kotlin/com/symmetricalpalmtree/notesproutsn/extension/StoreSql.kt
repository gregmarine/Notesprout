package com.symmetricalpalmtree.notesproutsn.extension

/**
 * Names an extension may give its own tables and indexes (arc 22 / X1): `^[a-z][a-z0-9_]{0,62}$`
 * and not in a host-reserved space. The reserved prefixes protect what the file holds besides the
 * extension's tables — the host's own `host_*` table, SQLite's `sqlite_*` catalog, and the
 * `room_*` / `android_*` names the Room era and the platform mint — and [StoreSql] refuses **any**
 * identifier token in one of them, quoted or not, in every statement kind.
 */
object StoreNames {
    private val SHAPE = Regex("^[a-z][a-z0-9_]{0,62}$")
    val RESERVED_PREFIXES: List<String> = listOf("host_", "sqlite_", "room_", "android_")

    fun isReserved(name: String): Boolean {
        val n = name.lowercase()
        return RESERVED_PREFIXES.any { n.startsWith(it) }
    }

    fun isValid(name: String): Boolean = SHAPE.matches(name) && !isReserved(name)
}

/**
 * The statement validator (arc 22 / X1) — pure and shared, so an extension can pre-check what the
 * host will refuse. A tiny tokenizer honest about `'…'`, `"…"`, `` `…` ``, `[…]`, `--` and `/* */`
 * feeds a handful of rules; nothing here parses SQL, it only refuses shapes the seam does not carry:
 *
 * - **One statement**: no `;` outside literals and comments (one trailing `;` is tolerated).
 * - **The head keyword** decides the kind: `SELECT` / `WITH` for [checkQuery], `INSERT` /
 *   `REPLACE` / `UPDATE` / `DELETE` / `WITH` for [checkExec], `CREATE` / `ALTER` / `DROP` for
 *   [checkDdl]. A query may not smuggle a write under `WITH` (`INSERT`/`UPDATE`/`DELETE`/
 *   `REPLACE INTO` are refused in a query anywhere).
 * - **The denylist**, anywhere in the token stream: `ATTACH DETACH PRAGMA VACUUM CREATE DROP ALTER
 *   BEGIN COMMIT ROLLBACK SAVEPOINT RELEASE REINDEX ANALYZE load_extension` (DDL keeps its own
 *   head word and refuses a second one, plus `VIEW TRIGGER VIRTUAL TEMP TEMPORARY`).
 * - **Reserved names**: every identifier token — bare or quoted — in a [StoreNames] reserved space.
 * - **Positional binds only** (`?`, `?NNN`); `:name` / `@name` / `$name` are refused — a name is
 *   one more parser.
 * - **DDL shape**: `CREATE TABLE`, `CREATE [UNIQUE] INDEX … ON`, `ALTER TABLE … ADD [COLUMN] |
 *   RENAME TO | RENAME [COLUMN] … TO`, `DROP TABLE|INDEX`, each with its `IF [NOT] EXISTS`; the
 *   created / altered / dropped object's name is [StoreNames.isValid] and bare.
 *
 * Every refusal is an `IllegalArgumentException` whose message says which rule.
 */
object StoreSql {

    enum class Kind { QUERY, EXEC, DDL }

    /** Refuses anything but a single `SELECT` / `WITH … SELECT`. */
    fun checkQuery(sql: String) = check(sql, Kind.QUERY)

    /** Refuses anything but a single `INSERT` / `REPLACE` / `UPDATE` / `DELETE` / `WITH …` write. */
    fun checkExec(sql: String) = check(sql, Kind.EXEC)

    /** Refuses anything but one supported DDL statement (see the class doc). */
    fun checkDdl(sql: String) = check(sql, Kind.DDL)

    /** True for a `CREATE TABLE` (the schema's table-count cap counts these). Assumes [checkDdl] passed. */
    fun createsTable(sql: String): Boolean {
        val t = statementTokens(sql)
        return t.size >= 2 && t[0].isWord("CREATE") && t[1].isWord("TABLE")
    }

    fun check(sql: String, kind: Kind) {
        val tokens = statementTokens(sql)
        require(tokens.isNotEmpty()) { "empty statement" }
        val head = tokens[0]
        require(head.kind == T.WORD) { "a statement starts with a keyword" }
        val heads = when (kind) {
            Kind.QUERY -> QUERY_HEADS
            Kind.EXEC -> EXEC_HEADS
            Kind.DDL -> DDL_HEADS
        }
        require(head.upper in heads) { "${kind.name.lowercase()} cannot start with ${head.text}" }
        val deny = if (kind == Kind.DDL) DDL_DENY else DENY
        for ((i, t) in tokens.withIndex()) {
            when (t.kind) {
                T.WORD -> {
                    require(t.upper !in deny) { "${t.text} is not allowed" }
                    if (kind == Kind.DDL && i > 0) require(t.upper !in DDL_HEADS) { "one statement — ${t.text} again" }
                    if (kind == Kind.QUERY) {
                        require(t.upper !in QUERY_WRITE_WORDS) { "a query cannot ${t.text}" }
                        if (t.upper == "REPLACE" && tokens.getOrNull(i + 1)?.isWord("INTO") == true) {
                            throw IllegalArgumentException("a query cannot REPLACE INTO")
                        }
                    }
                    require(!StoreNames.isReserved(t.text)) { "${t.text} is a host-reserved name" }
                }
                T.QUOTED -> require(!StoreNames.isReserved(t.text)) { "${t.text} is a host-reserved name" }
                T.NAMED_BIND -> throw IllegalArgumentException("named binds are not supported (${t.text}) — use ?")
                else -> Unit
            }
        }
        if (kind == Kind.DDL) checkDdlShape(tokens)
    }

    // ── DDL shape ──────

    private fun checkDdlShape(t: List<Token>) {
        var i = 1
        fun word(): Token = t.getOrNull(i) ?: throw IllegalArgumentException("incomplete DDL")
        fun expect(vararg any: String): Token {
            val w = word()
            require(w.kind == T.WORD && w.upper in any) { "expected ${any.joinToString(" | ")} at '${w.text}'" }
            i++
            return w
        }
        fun skipIfExists(not: Boolean) {
            if (t.getOrNull(i)?.isWord("IF") == true) {
                i++
                if (not) expect("NOT")
                expect("EXISTS")
            }
        }
        fun ownName(): String {
            val w = word()
            require(w.kind == T.WORD) { "a table / index name is bare, not quoted ('${w.text}')" }
            require(StoreNames.isValid(w.text)) { "'${w.text}' is not a store name (lowercase, [a-z0-9_], 1..63)" }
            i++
            return w.text
        }
        when (t[0].upper) {
            "CREATE" -> {
                val what = expect("TABLE", "INDEX", "UNIQUE")
                if (what.upper == "UNIQUE") expect("INDEX")
                skipIfExists(not = true)
                ownName()
                if (what.upper != "TABLE") {
                    expect("ON")
                    ownName()
                }
            }
            "ALTER" -> {
                expect("TABLE")
                ownName()
                when (expect("ADD", "RENAME").upper) {
                    "ADD" -> Unit
                    "RENAME" -> if (t.getOrNull(i)?.isWord("TO") == true) { i++; ownName() }
                }
            }
            "DROP" -> {
                expect("TABLE", "INDEX")
                skipIfExists(not = false)
                ownName()
            }
        }
    }

    // ── Tokens ──────

    enum class T { WORD, QUOTED, STRING, NUMBER, BIND, NAMED_BIND, PUNCT, SEMI }

    class Token(val kind: T, val text: String) {
        val upper: String get() = text.uppercase()
        fun isWord(w: String): Boolean = kind == T.WORD && upper == w
        override fun toString() = "$kind($text)"
    }

    /** The tokens of one statement: length-capped, one trailing `;` dropped, any other refused. */
    private fun statementTokens(sql: String): List<Token> {
        require(sql.isNotBlank()) { "empty statement" }
        require(sql.length <= ExtensionContract.STORE_MAX_SQL_CHARS) {
            "statement exceeds ${ExtensionContract.STORE_MAX_SQL_CHARS} chars (${sql.length})"
        }
        val tokens = tokenize(sql)
        val body = if (tokens.lastOrNull()?.kind == T.SEMI) tokens.dropLast(1) else tokens
        require(body.none { it.kind == T.SEMI }) { "one statement per call — no ';'" }
        return body
    }

    fun tokenize(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = sql.length
        fun isIdentStart(c: Char) = c.isLetter() || c == '_'
        fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    val end = sql.indexOf('\n', i)
                    i = if (end < 0) n else end + 1
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    require(end >= 0) { "unterminated comment" }
                    i = end + 2
                }
                c == '\'' -> { val (text, next) = quoted(sql, i, '\'', '\''); out += Token(T.STRING, text); i = next }
                c == '"' -> { val (text, next) = quoted(sql, i, '"', '"'); out += Token(T.QUOTED, text); i = next }
                c == '`' -> { val (text, next) = quoted(sql, i, '`', '`'); out += Token(T.QUOTED, text); i = next }
                c == '[' -> { val (text, next) = quoted(sql, i, '[', ']'); out += Token(T.QUOTED, text); i = next }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentPart(sql[j])) j++
                    out += Token(T.WORD, sql.substring(i, j)); i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '.' || sql[j] == '_')) j++
                    out += Token(T.NUMBER, sql.substring(i, j)); i = j
                }
                c == '?' -> {
                    var j = i + 1
                    while (j < n && sql[j].isDigit()) j++
                    out += Token(T.BIND, sql.substring(i, j)); i = j
                }
                (c == ':' || c == '@' || c == '$') && i + 1 < n && isIdentStart(sql[i + 1]) -> {
                    var j = i + 2
                    while (j < n && isIdentPart(sql[j])) j++
                    out += Token(T.NAMED_BIND, sql.substring(i, j)); i = j
                }
                c == ';' -> { out += Token(T.SEMI, ";"); i++ }
                else -> { out += Token(T.PUNCT, c.toString()); i++ }
            }
        }
        return out
    }

    /** A quoted run from [start] (at the opening quote): the inner text with doubled closers
     *  unescaped, and the index after the closing quote. Unterminated → `IllegalArgumentException`. */
    private fun quoted(sql: String, start: Int, open: Char, close: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            if (c == close) {
                if (open == close && i + 1 < n && sql[i + 1] == close) { sb.append(close); i += 2; continue }
                return sb.toString() to i + 1
            }
            sb.append(c); i++
        }
        throw IllegalArgumentException("unterminated $open…$close")
    }

    // ── Word sets ──────

    private val QUERY_HEADS = setOf("SELECT", "WITH")
    private val EXEC_HEADS = setOf("INSERT", "REPLACE", "UPDATE", "DELETE", "WITH")
    private val DDL_HEADS = setOf("CREATE", "ALTER", "DROP")
    private val QUERY_WRITE_WORDS = setOf("INSERT", "UPDATE", "DELETE")

    /** Refused anywhere in a query or an exec. */
    val DENY: Set<String> = setOf(
        "ATTACH", "DETACH", "PRAGMA", "VACUUM", "CREATE", "DROP", "ALTER", "BEGIN", "COMMIT",
        "ROLLBACK", "SAVEPOINT", "RELEASE", "REINDEX", "ANALYZE", "LOAD_EXTENSION",
    )

    /** Refused anywhere in DDL: the list above minus the DDL heads, plus the object kinds v6 does not carry. */
    val DDL_DENY: Set<String> = (DENY - DDL_HEADS) + setOf("VIEW", "TRIGGER", "VIRTUAL", "TEMP", "TEMPORARY")
}
