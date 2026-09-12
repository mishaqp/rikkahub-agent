package me.rerere.search.extract

/**
 * Generator for the opaque handle a web tool hands back for a page it has read.
 *
 * The identifier is *not* derived from the URL: it is fresh randomness, so it cannot be reversed
 * into an address, two reads of the same page get different handles, and the hostname never
 * appears in the value. 16 random bytes (128 bits) make an accidental collision negligible.
 */
object WebSourceId {

    const val PREFIX = "src_"

    /** Random bytes behind one identifier; 16 bytes keeps the value short and the collision risk nil. */
    const val RANDOM_BYTES = 16

    /** Total length of a well-formed identifier. */
    const val LENGTH = PREFIX.length + RANDOM_BYTES * 2

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Cryptographically strong by default. [random] exists as a seam for tests that need
     * reproducible values; production callers never pass it.
     */
    private val defaultRandom: java.util.Random = java.security.SecureRandom()

    /** A fresh identifier, unrelated to any URL. */
    fun newId(random: java.util.Random = defaultRandom): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        val builder = StringBuilder(LENGTH)
        builder.append(PREFIX)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[(value shr 4) and 0x0F]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /** True when [value] has the shape [newId] produces: the prefix plus lowercase hex. */
    fun isWellFormed(value: String): Boolean =
        value.length == LENGTH &&
            value.startsWith(PREFIX) &&
            value.substring(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * A deterministic generator for tests and tooling that need stable identifiers; not used by
     * the tools themselves.
     */
    fun deterministic(seed: Long): () -> String {
        val random = java.util.Random(seed)
        return { newId(random) }
    }
}

/**
 * Where a cached page came from. A browser snapshot is not an HTTP response, so the two must be
 * distinguishable in the envelope instead of a browser page pretending to have a status code.
 */
enum class WebSourceOrigin(val wireName: String) {
    /** Fetched over HTTP by web_fetch / web_extract. */
    HTTP("http"),

    /** Read out of the live rendered page by a browser tool. */
    BROWSER("browser"),
}

/**
 * One cached page: the cleaned text plus the metadata derived from the same source.
 *
 * This type intentionally has no field for request headers, response headers, cookies,
 * credentials or a POST body, so a cache entry physically cannot leak them. A browser snapshot
 * stores only rendered, visible text - never DOM, script, style or form state.
 *
 * [status] is null for a [WebSourceOrigin.BROWSER] source: a rendered page has no HTTP status of
 * its own, and inventing a 200 would make the envelope lie about where the text came from.
 */
data class WebSource(
    val sourceId: String,
    val url: String,
    val status: Int? = null,
    val mode: ExtractMode,
    val title: String? = null,
    val siteName: String? = null,
    val description: String? = null,
    val language: String? = null,
    val text: String = "",
    val bodyTruncated: Boolean = false,
    val storedAtMillis: Long = 0L,
    val origin: WebSourceOrigin = WebSourceOrigin.HTTP,
)

/**
 * Bounded, expiring, in-memory store of cleaned pages, shared by the web tools so a later
 * `source_id` call can answer without touching the network.
 *
 * Policy (all four are enforced together):
 *  * at most [MAX_ENTRIES] entries;
 *  * at most [MAX_BYTES] of stored text and metadata, measured in UTF-8 bytes;
 *  * every entry expires [TTL_MILLIS] after it was stored;
 *  * eviction is least-recently-used, so re-reading a source keeps it alive.
 *
 * Thread-safe: every read and write runs under one lock. The maps hold no Android or IO
 * handles, so this is plain JVM state and dies with the process - nothing is written to disk.
 */
class BoundedWebSourceCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
    private val ttlMillis: Long = TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val source: WebSource, val bytes: Long)

    private val lock = Any()

    /** Access-ordered, so iteration runs least- to most-recently used: the LRU end is first. */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private var bytes = 0L

    /**
     * Store [source], evicting as needed. Returns false when the entry alone exceeds
     * [maxBytes] - the caller keeps serving it, it just is not kept.
     */
    fun put(source: WebSource): Boolean = synchronized(lock) {
        purgeExpiredLocked()
        val entry = Entry(source, sizeOf(source))
        if (entry.bytes > maxBytes) return false

        entries.remove(source.sourceId)?.let { bytes -= it.bytes }
        entries[source.sourceId] = entry
        bytes += entry.bytes
        trimLocked()
        true
    }

    /** The live entry for [sourceId], refreshing its recency, or null when absent or expired. */
    fun get(sourceId: String): WebSource? = synchronized(lock) {
        val entry = entries[sourceId] ?: return null
        if (isExpired(entry.source)) {
            entries.remove(sourceId)
            bytes -= entry.bytes
            return null
        }
        entry.source
    }

    fun contains(sourceId: String): Boolean = get(sourceId) != null

    fun remove(sourceId: String) = synchronized(lock) {
        entries.remove(sourceId)?.let { bytes -= it.bytes }
        Unit
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0L
        Unit
    }

    /** Live entry count, expired entries excluded. */
    fun size(): Int = synchronized(lock) {
        purgeExpiredLocked()
        entries.size
    }

    /** Bytes currently held, expired entries excluded. */
    fun bytes(): Long = synchronized(lock) {
        purgeExpiredLocked()
        bytes
    }

    fun ttlMillis(): Long = ttlMillis

    private fun trimLocked() {
        while (entries.size > maxEntries || bytes > maxBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            bytes -= eldest.value.bytes
        }
    }

    private fun purgeExpiredLocked() {
        if (entries.isEmpty()) return
        val now = clock()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isExpired(entry.value.source, now)) {
                bytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    private fun isExpired(source: WebSource, now: Long = clock()): Boolean =
        now - source.storedAtMillis > ttlMillis

    private fun sizeOf(source: WebSource): Long =
        utf8Bytes(source.sourceId) + utf8Bytes(source.url) + utf8Bytes(source.text) +
            utf8Bytes(source.title) + utf8Bytes(source.siteName) +
            utf8Bytes(source.description) + utf8Bytes(source.language)

    private fun utf8Bytes(value: String?): Long =
        if (value.isNullOrEmpty()) 0L else value.toByteArray(Charsets.UTF_8).size.toLong()

    companion object {
        /** Sources kept at once. Small enough that a phone never notices the memory. */
        const val MAX_ENTRIES = 24

        /** Total budget for stored text and metadata. */
        const val MAX_BYTES = 4L * 1024 * 1024

        /** How long a source stays usable after it was read. */
        const val TTL_MILLIS = 45L * 60 * 1000
    }
}

/**
 * The cache the web tools share for the lifetime of the process, so `web_fetch` can hand out a
 * `source_id` that `web_extract` (or a later `web_fetch`) reuses without a second request.
 */
val webSourceCache: BoundedWebSourceCache = BoundedWebSourceCache()
