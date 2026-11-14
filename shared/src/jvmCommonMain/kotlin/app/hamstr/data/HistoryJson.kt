package app.hamstr.data

/**
 * downloads.json, written and read straight from and into [DownloadTask]s: no JSONObject per
 * entry, no tokenizer that reads a character at a time through a Reader. The history is the
 * biggest file Hamstr keeps (thousands of entries for someone who follows channels), read on the
 * way to the first screen and written again on every change of status, so this is several times
 * faster than org.json and makes far less garbage. The format stays the one org.json wrote:
 * a missing or null value means its default, and a value of another type is read the way
 * org.json's opt… calls read it, so old files, backups and newer versions' entries read as before.
 */
internal object HistoryJson {
    class Read(val tasks: List<DownloadTask>, val skipped: Int)

    fun write(tasks: List<DownloadTask>): String {
        val out = StringBuilder(tasks.size * 640 + 2)
        out.append('[')
        tasks.forEachIndexed { i, t ->
            if (i > 0) out.append(',')
            Writer(out).entry(t)
        }
        return out.append(']').toString()
    }

    /** Every entry on its own: one that can't be made into a download (no id, not an object) is skipped and counted. */
    fun read(text: String): Read {
        val p = Parser(text)
        p.skipBom()
        p.ws()
        p.expect('[')
        val tasks = ArrayList<DownloadTask>()
        var skipped = 0
        p.ws()
        var more = p.peek() != ']'
        if (!more) p.pos++
        while (more) {
            p.ws()
            if (p.peek() == '{') {
                val e = p.entry()
                val task = runCatching { e.task() }.getOrNull()
                if (task != null) tasks += task else skipped++
            } else {
                p.value()
                skipped++
            }
            p.ws()
            when (p.next()) {
                ',' -> {}
                ']' -> more = false
                else -> p.fail("',' or ']' expected")
            }
        }
        p.ws()
        if (p.pos < text.length) p.fail("text after the list")
        return Read(tasks, skipped)
    }

    private class Writer(val out: StringBuilder) {
        private var first = true

        private fun key(k: String) {
            if (first) out.append('{') else out.append(',')
            first = false
            out.append('"').append(k).append("\":")
        }

        fun str(k: String, v: String?) { if (v != null) { key(k); quote(v) } }
        fun num(k: String, v: Long) { key(k); out.append(v) }
        fun num(k: String, v: Int) { key(k); out.append(v) }
        fun bool(k: String, v: Boolean) { key(k); out.append(v) }
        // org.json refused NaN and infinity and so lost the whole save; this writes 0 instead.
        fun num(k: String, v: Float) { key(k); if (v.isFinite()) out.append(v) else out.append('0') }

        fun quote(s: String) {
            out.append('"')
            var from = 0
            for (i in s.indices) {
                val c = s[i]
                val escape = when {
                    c == '"' -> "\\\""
                    c == '\\' -> "\\\\"
                    c == '\n' -> "\\n"
                    c == '\r' -> "\\r"
                    c == '\t' -> "\\t"
                    c < ' ' || c == ' ' || c == ' ' -> null
                    // A lone half of a surrogate pair would turn into '?' in UTF-8; escaped, it survives.
                    c.isSurrogate() && !paired(s, i) -> null
                    else -> continue
                }
                out.append(s, from, i)
                if (escape != null) out.append(escape) else {
                    out.append("\\u")
                    val code = c.code
                    for (shift in intArrayOf(12, 8, 4, 0)) out.append(HEX[(code shr shift) and 15])
                }
                from = i + 1
            }
            out.append(s, from, s.length).append('"')
        }

        private fun paired(s: String, i: Int): Boolean {
            val c = s[i]
            return if (c.isHighSurrogate()) i + 1 < s.length && s[i + 1].isLowSurrogate()
            else i > 0 && s[i - 1].isHighSurrogate()
        }

        fun entry(t: DownloadTask) {
            str("id", t.id); str("url", t.url); str("title", t.title)
            str("thumbnail", t.thumbnail); str("source", t.source); num("duration", t.durationSec)
            str("mode", t.choice.mode.name); num("height", t.choice.height ?: 0); str("container", t.choice.container)
            str("audioFormat", t.choice.audioFormat); bool("subtitles", t.choice.subtitles); str("customFormat", t.choice.customFormat); bool("open", t.choice.open)
            str("status", t.status.name); num("progress", t.progress)
            str("filePath", t.filePath); str("contentUri", t.contentUri); num("size", t.sizeBytes)
            str("error", t.error); num("createdAt", t.createdAt); num("finishedAt", t.finishedAt)
            str("group", t.group); str("groupTitle", t.groupTitle)
            str("versionOf", t.versionOf); str("replaces", t.replaces); num("videoHeight", t.videoHeight)
            str("uploader", t.uploader); str("mediaKey", t.mediaKey); bool("hidden", t.hidden)
            str("sha256", t.sha256); num("hashedSize", t.hashedSize); num("hashedModified", t.hashedModified)
            bool("anytime", t.anytime); bool("mobileData", t.mobileData); bool("tonight", t.tonight)
            str("channelUrl", t.channelUrl)
            if (first) out.append('{')
            out.append('}')
        }
    }

    /** A JSON null, and an object or list where a plain value was expected (read like org.json's opt… read them). */
    private object Null
    private object Nested

    /** One entry's fields as they came; [task] turns them into a download the way DownloadStore always read them. */
    private class Entry {
        val values = arrayOfNulls<Any>(KEYS.size)

        private fun raw(k: Int): Any? = values[k]?.takeIf { it !== Null }

        /** org.json's optString: a missing or null value is [default], anything else its text. */
        fun string(k: Int, default: String = ""): String = when (val v = raw(k)) {
            null -> default
            is String -> v
            Nested -> "" // org.json would give the nested JSON's text; no field of a download holds one
            else -> v.toString()
        }

        fun stringOrNull(k: Int): String? = if (raw(k) == null) null else string(k).ifEmpty { null }

        /** getString: there and not null, or the entry can't be read. */
        fun required(k: Int): String = when (val v = raw(k)) {
            is String -> v
            null, Nested -> error("no ${KEYS[k]}")
            else -> v.toString()
        }

        private fun number(k: Int): Number? = when (val v = raw(k)) {
            is Number -> v
            is String -> v.trim().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() }
            else -> null
        }

        fun int(k: Int): Int = number(k)?.toInt() ?: 0
        fun long(k: Int): Long = number(k)?.toLong() ?: 0L
        fun double(k: Int, default: Double): Double = number(k)?.toDouble() ?: default

        fun bool(k: Int): Boolean = when (val v = raw(k)) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }

        fun task() = DownloadTask(
            id = required(ID),
            url = required(URL),
            title = string(TITLE),
            thumbnail = stringOrNull(THUMBNAIL),
            source = stringOrNull(SOURCE),
            durationSec = int(DURATION),
            choice = Choice(
                mode = runCatching { Mode.valueOf(string(MODE, "Video")) }.getOrDefault(Mode.Video),
                height = int(HEIGHT).takeIf { it > 0 },
                container = string(CONTAINER, "mp4"),
                audioFormat = string(AUDIO_FORMAT, "opus"),
                subtitles = bool(SUBTITLES),
                customFormat = string(CUSTOM_FORMAT),
                open = bool(OPEN),
            ),
            // A state this version doesn't know (from a newer Hamstr) waits to be tried again.
            status = runCatching { Status.valueOf(string(STATUS, "Queued")) }.getOrDefault(Status.Paused),
            progress = double(PROGRESS, 0.0).toFloat(),
            filePath = stringOrNull(FILE_PATH),
            contentUri = stringOrNull(CONTENT_URI),
            sizeBytes = long(SIZE),
            error = stringOrNull(ERROR),
            createdAt = long(CREATED_AT),
            finishedAt = long(FINISHED_AT),
            group = stringOrNull(GROUP),
            groupTitle = stringOrNull(GROUP_TITLE),
            versionOf = stringOrNull(VERSION_OF),
            replaces = stringOrNull(REPLACES),
            videoHeight = int(VIDEO_HEIGHT),
            uploader = stringOrNull(UPLOADER),
            mediaKey = stringOrNull(MEDIA_KEY),
            hidden = bool(HIDDEN),
            sha256 = stringOrNull(SHA256),
            hashedSize = long(HASHED_SIZE),
            hashedModified = long(HASHED_MODIFIED),
            anytime = bool(ANYTIME),
            mobileData = bool(MOBILE_DATA),
            tonight = bool(TONIGHT),
            channelUrl = stringOrNull(CHANNEL_URL),
        )
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(what: String): Nothing = throw IllegalArgumentException("downloads.json: $what at $pos")

        fun peek(): Char = if (pos < s.length) s[pos] else fail("unexpected end")
        fun next(): Char = peek().also { pos++ }
        fun expect(c: Char) { if (next() != c) fail("'$c' expected") }

        fun skipBom() { if (pos < s.length && s[pos] == '﻿') pos++ }

        fun ws() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
            }
        }

        /** An object as a download's fields; keys it doesn't know are read past. */
        fun entry(): Entry {
            val e = Entry()
            expect('{')
            ws()
            if (peek() == '}') { pos++; return e }
            while (true) {
                ws()
                if (peek() != '"') fail("key expected")
                val k = KEY_INDEX[string()]
                ws()
                expect(':')
                ws()
                val v = value()
                if (k != null) e.values[k] = v
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> return e
                    else -> fail("',' or '}' expected")
                }
            }
        }

        /** A plain value as String, Long, Double, Boolean or [Null]; an object or list is read past as [Nested]. */
        fun value(): Any = when (val c = peek()) {
            '"' -> string()
            '{', '[' -> { skipNested(); Nested }
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("value expected")
        }

        private fun word(w: String, v: Any): Any {
            if (!s.startsWith(w, pos)) fail("'$w' expected")
            pos += w.length
            return v
        }

        private fun number(): Any {
            val start = pos
            var whole = true
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9' || c == '-' || c == '+') pos++
                else if (c == '.' || c == 'e' || c == 'E') { whole = false; pos++ }
                else break
            }
            val text = s.substring(start, pos)
            return (if (whole) text.toLongOrNull() else null) ?: text.toDoubleOrNull() ?: fail("number expected")
        }

        fun string(): String {
            expect('"')
            val start = pos
            // Most strings have nothing escaped: one substring, no builder.
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos]
                if (c == '"') return s.substring(start, pos).also { pos++ }
                if (c == '\\') break
                pos++
            }
            val out = StringBuilder(pos - start + 16).append(s, start, pos)
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) fail("short \\u escape")
                            out.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                            pos += 4
                        }
                        else -> fail("bad escape")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun skipNested() {
            var depth = 0
            while (true) {
                when (peek()) {
                    '"' -> { string(); if (depth == 0) return }
                    '{', '[' -> { pos++; depth++ }
                    '}', ']' -> { pos++; if (--depth == 0) return }
                    else -> pos++
                }
            }
        }
    }

    private const val ID = 0
    private const val URL = 1
    private const val TITLE = 2
    private const val THUMBNAIL = 3
    private const val SOURCE = 4
    private const val DURATION = 5
    private const val MODE = 6
    private const val HEIGHT = 7
    private const val CONTAINER = 8
    private const val AUDIO_FORMAT = 9
    private const val SUBTITLES = 10
    private const val CUSTOM_FORMAT = 11
    private const val OPEN = 12
    private const val STATUS = 13
    private const val PROGRESS = 14
    private const val FILE_PATH = 15
    private const val CONTENT_URI = 16
    private const val SIZE = 17
    private const val ERROR = 18
    private const val CREATED_AT = 19
    private const val FINISHED_AT = 20
    private const val GROUP = 21
    private const val GROUP_TITLE = 22
    private const val VERSION_OF = 23
    private const val REPLACES = 24
    private const val VIDEO_HEIGHT = 25
    private const val UPLOADER = 26
    private const val MEDIA_KEY = 27
    private const val HIDDEN = 28
    private const val SHA256 = 29
    private const val HASHED_SIZE = 30
    private const val HASHED_MODIFIED = 31
    private const val ANYTIME = 32
    private const val MOBILE_DATA = 33
    private const val TONIGHT = 34
    private const val CHANNEL_URL = 35

    // In the order of the constants above.
    private val KEYS = arrayOf(
        "id", "url", "title", "thumbnail", "source", "duration", "mode", "height", "container", "audioFormat",
        "subtitles", "customFormat", "open", "status", "progress", "filePath", "contentUri", "size", "error", "createdAt",
        "finishedAt", "group", "groupTitle", "versionOf", "replaces", "videoHeight", "uploader", "mediaKey", "hidden", "sha256",
        "hashedSize", "hashedModified", "anytime", "mobileData", "tonight", "channelUrl",
    )
    private val KEY_INDEX: Map<String, Int> = HashMap<String, Int>(KEYS.size * 2).apply { KEYS.forEachIndexed { i, k -> put(k, i) } }
    private val HEX = "0123456789abcdef".toCharArray()
}
