package com.opencurv.curvescore.io

/**
 * Minimal open-addressing set of primitive `long`s.
 *
 * Used only by [OsmPbfReader] to de-duplicate the node ids referenced by the
 * ways the scorer actually needs, during the first of its two passes. A plain
 * `java.util.HashSet<Long>` would box every id (object header + Long box +
 * HashMap.Node, roughly 40+ bytes per entry); this is a `LongArray` under
 * open addressing with linear probing, roughly 8-16 bytes per entry at the
 * load factor used here. For tens of millions of node ids (a German
 * Bundesland) that difference is hundreds of MB to low GB, and it exists only
 * transiently during pass 1 -- see 1.Doku/Kurven_Score.md, section 9.
 *
 * OSM node ids are always positive (IDs start at 1), so `Long.MIN_VALUE` is
 * safe to use as the "empty slot" sentinel.
 */
internal class LongHashSet(initialCapacity: Int = 1 shl 16) {
    private var table: LongArray = LongArray(nextPow2(initialCapacity)) { EMPTY }
    private var mask: Int = table.size - 1
    var size: Int = 0
        private set

    fun add(v: Long) {
        require(v != EMPTY) { "node id $v clashes with the empty-slot sentinel" }
        if ((size + 1) * 2 > table.size) grow()
        insert(table, mask, v)?.let { size++ }
    }

    /** Returns the sorted array of distinct values added so far. */
    fun toSortedArray(): LongArray {
        val out = LongArray(size)
        var k = 0
        for (v in table) if (v != EMPTY) out[k++] = v
        out.sort()
        return out
    }

    /**
     * Drops the backing array so the JVM can reclaim it immediately, rather
     * than waiting for this object to fall out of scope. Call once
     * [toSortedArray] has produced the (much smaller, exactly-sized) result -
     * for a large Bundesland the open-addressing table can transiently be up
     * to ~4x the final distinct-id count.
     */
    fun clear() {
        table = EMPTY_TABLE
        mask = 0
        size = 0
    }

    companion object {
        private const val EMPTY = Long.MIN_VALUE
        private val EMPTY_TABLE = LongArray(1) { EMPTY }
    }

    private fun grow() {
        val old = table
        val bigger = LongArray(old.size * 2) { EMPTY }
        val biggerMask = bigger.size - 1
        for (v in old) if (v != EMPTY) insert(bigger, biggerMask, v)
        table = bigger
        mask = biggerMask
    }

    /** Inserts v into `t` (mask = t.size-1); returns v if it was newly added, else null. */
    private fun insert(t: LongArray, m: Int, v: Long): Long? {
        var i = (hash(v) and m)
        while (true) {
            val cur = t[i]
            if (cur == EMPTY) { t[i] = v; return v }
            if (cur == v) return null
            i = (i + 1) and m
        }
    }

    private fun hash(v: Long): Int {
        // 64-bit mix (splitmix64 finalizer) so consecutive OSM ids don't
        // cluster into consecutive buckets.
        var h = v xor (v ushr 30)
        h *= -0x40a7b892e31b1a47L
        h = h xor (h ushr 27)
        h *= -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return h.toInt()
    }

    private fun nextPow2(n: Int): Int {
        var p = 16
        while (p < n) p = p shl 1
        return p
    }
}
