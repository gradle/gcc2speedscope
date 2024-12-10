package gcc2speedscope

import java.math.MathContext
import java.math.RoundingMode
import java.util.*
import kotlin.math.pow

typealias Context = String

internal
class DefaultCollector {

    private
    data class Operation(
        val context: Context,
        val length: Long
    )

    private
    class Stats(val oid: String, val hash: String, val objectType: String) {
        val totalLength: Long get() = operations.sumOf(Operation::length)
        val count: Long get() = operations.size.toLong()
        val operations = Collections.synchronizedList(mutableListOf<Operation>())

        val hasDetails: Boolean get() = totalLength > 0

        fun contextInfo(): String {
            val contexts = operations.map { it.context }.toSet().size
            return contexts.takeIf { it > 1 }?.let { "in $it contexts " } ?: ""
        }

        fun details(): String? {
            if (!hasDetails) return null
            val lengths = operations.map(Operation::length)
            val maxLength = lengths.max()
            val minLength = lengths.min()
            val meanLength = lengths.average()
            val stdDeviation = Math.sqrt(lengths.map { (it - meanLength).pow(2) }.average())
            return "(total: $totalLength, max: $maxLength, min: $minLength, avg: ${meanLength.toBigDecimal()}, stdDev: ${stdDeviation.toBigDecimal().toEngineeringString()})"
        }

        fun addStat(context: Context, length: Long?): Stats {
            require(length != 0L)
            operations.add(Operation(context, length ?: 0))
            return this
        }

        private
        fun Double.toBigDecimal() = toBigDecimal(MC)

        companion object {
            private val MC: MathContext = MathContext(5, RoundingMode.HALF_EVEN)
        }
    }

    private val stats: MutableMap<String, Stats> = HashMap()

    fun collect(context: Context, objectType: String, oid: String, hash: String, length: Long) {
        oid.takeUnless {
            0L == length
        }?.let {
            stats.computeIfAbsent("${oid}:${hash}") { Stats(oid, hash, objectType) }.addStat(context, length)
        }
    }


    fun printStats(sortByLength: Boolean = true) {
        val count = stats.size
        val cut = minOf(200, count)
        val sorter = if (sortByLength) Stats::totalLength else Stats::count
        val sorted = stats.asSequence()
            .filter { it.value.count > 1 }
            .sortedByDescending {
                sorter.invoke(it.value)
            }
        val topObjects = sorted.take(cut)
        val totalLength = stats.values.sumOf { it.totalLength }
        val totalCount = stats.values.sumOf { it.count }
        print("[stats] Stats: $count objects, $totalCount locations, $totalLength total length")
        if (count > cut) {
            val topTotalLength = topObjects.sumOf { it.value.totalLength }
            val topTotalCount = topObjects.sumOf { it.value.count }
            print(" - top $cut objects: $topTotalCount locations, $topTotalLength total length")
        }
        println()
        topObjects
            .forEach {
                val stats = it.value
                println("[stats] ${stats.count} - ${stats.oid} : ${stats.objectType} - ${stats.contextInfo()}")
                if (stats.hasDetails) {
                    println("[stats] ${stats.details()}")
                }
            }
        println("[stats] End of stats")
    }
}