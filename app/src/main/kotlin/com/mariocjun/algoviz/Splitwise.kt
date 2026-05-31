// Splitwise — expense ledger + greedy debt simplification.
//
// All money is stored in CENTS (Long) to avoid floating-point drift across
// many additions. Every splitter distributes its remainder cent-by-cent so the
// per-participant shares always sum back to the expense total exactly.
//
// Logic-only module: no Android dependencies, no I/O. Unit-tested on the JVM
// via app/src/test/kotlin/.../SplitwiseTest.kt. UI lives elsewhere.
package com.mariocjun.algoviz

/** A participant. Plain string id so the caller picks the naming policy. */
typealias Person = String

/**
 * How an expense is shared. Each variant takes the set of participants
 * (which always includes the payer's contribution to their own share) plus
 * the parameter that defines the split.
 */
sealed class SplitMode {
    /** Even split across the listed participants. Remainder cents distributed
     *  one-by-one to the first names in iteration order, so the shares sum
     *  back to the expense total exactly. */
    data class Equal(val participants: List<Person>) : SplitMode()

    /** Each participant owes a fixed cent amount. The sum MUST equal the
     *  expense total — addExpense throws IllegalArgumentException otherwise. */
    data class Exact(val owedCents: Map<Person, Long>) : SplitMode()

    /** Each participant owes a percentage of the total. Percentages must sum
     *  to 100.0 within a small epsilon. Remainder cents are distributed in
     *  iteration order. */
    data class Percent(val percents: Map<Person, Double>) : SplitMode()

    /** Each participant has an integer share count (e.g. 1:2:3); each owes a
     *  proportional slice. Shares must be positive and sum to > 0. */
    data class Shares(val shares: Map<Person, Int>) : SplitMode()
}

data class Expense(
    val payer: Person,
    val amountCents: Long,
    val split: SplitMode,
    val description: String = "",
)

/** A single edge in the settlement plan: from → to for amountCents. */
data class Settlement(val from: Person, val to: Person, val amountCents: Long)

class Ledger {
    private val expenses = mutableListOf<Expense>()
    private val knownPeople = linkedSetOf<Person>()

    fun addExpense(e: Expense) {
        require(e.amountCents > 0) { "amount must be positive (got ${e.amountCents})" }
        val shares = splitShares(e.amountCents, e.split)
        require(shares.values.sum() == e.amountCents) {
            "split shares ${shares.values.sum()} do not match amount ${e.amountCents}"
        }
        expenses.add(e)
        knownPeople.add(e.payer)
        knownPeople.addAll(shares.keys)
    }

    fun people(): List<Person> = knownPeople.toList()

    fun expenses(): List<Expense> = expenses.toList()

    /** Net balance per person: paid − owed share. Positive = net creditor. */
    fun balances(): Map<Person, Long> {
        val out = linkedMapOf<Person, Long>().apply {
            knownPeople.forEach { put(it, 0L) }
        }
        for (e in expenses) {
            out.merge(e.payer, e.amountCents, Long::plus)
            for ((person, owed) in splitShares(e.amountCents, e.split)) {
                out.merge(person, -owed, Long::plus)
            }
        }
        return out
    }

    /**
     * Greedy debt simplification: repeatedly net the biggest creditor against
     * the biggest debtor until everyone is within 1 cent of zero. ~30-line
     * core, near-optimal — this is what Splitwise effectively does. The exact
     * minimum-edge solution is NP-hard (a partition variant).
     *
     * Ties on the same balance amount are broken by person id (alphabetical)
     * so the output is deterministic across runs.
     */
    fun settlements(): List<Settlement> {
        val bal = balances().toMutableMap()
        val out = mutableListOf<Settlement>()
        while (true) {
            val creditor = bal.entries
                .filter { it.value > 0 }
                .maxWithOrNull(compareBy({ it.value }, { it.key })) ?: break
            val debtor = bal.entries
                .filter { it.value < 0 }
                .minWithOrNull(compareBy({ it.value }, { it.key })) ?: break
            val pay = minOf(creditor.value, -debtor.value)
            if (pay <= 0) break
            out.add(Settlement(from = debtor.key, to = creditor.key, amountCents = pay))
            bal[creditor.key] = creditor.value - pay
            bal[debtor.key] = debtor.value + pay
        }
        return out
    }

    private fun splitShares(amount: Long, split: SplitMode): Map<Person, Long> = when (split) {
        is SplitMode.Equal -> equalSplit(amount, split.participants)
        is SplitMode.Exact -> {
            require(split.owedCents.isNotEmpty()) { "exact split needs at least one participant" }
            require(split.owedCents.values.all { it >= 0 }) { "exact shares must be non-negative" }
            split.owedCents
        }
        is SplitMode.Percent -> percentSplit(amount, split.percents)
        is SplitMode.Shares -> sharesSplit(amount, split.shares)
    }

    private fun equalSplit(amount: Long, participants: List<Person>): Map<Person, Long> {
        require(participants.isNotEmpty()) { "equal split needs at least one participant" }
        val n = participants.size.toLong()
        val base = amount / n
        val remainder = (amount - base * n).toInt()   // 0 .. n-1
        val out = linkedMapOf<Person, Long>()
        participants.forEachIndexed { i, p ->
            out.merge(p, base + if (i < remainder) 1L else 0L, Long::plus)
        }
        return out
    }

    private fun percentSplit(amount: Long, percents: Map<Person, Double>): Map<Person, Long> {
        require(percents.isNotEmpty()) { "percent split needs at least one participant" }
        val sum = percents.values.sum()
        require(kotlin.math.abs(sum - 100.0) < 0.001) {
            "percent shares must sum to 100 (got $sum)"
        }
        return distributeProportional(amount, percents.mapValues { it.value })
    }

    private fun sharesSplit(amount: Long, shares: Map<Person, Int>): Map<Person, Long> {
        require(shares.isNotEmpty()) { "shares split needs at least one participant" }
        require(shares.values.all { it > 0 }) { "shares must be positive" }
        return distributeProportional(amount, shares.mapValues { it.value.toDouble() })
    }

    private fun distributeProportional(amount: Long, weights: Map<Person, Double>): Map<Person, Long> {
        val total = weights.values.sum()
        require(total > 0.0) { "weight total must be positive" }
        val raw = linkedMapOf<Person, Long>()
        var floored = 0L
        for ((p, w) in weights) {
            val share = (amount.toDouble() * w / total).toLong()   // floor for non-negative
            raw[p] = share
            floored += share
        }
        var remainder = amount - floored
        if (remainder > 0) {
            for (p in weights.keys) {
                if (remainder <= 0) break
                raw.merge(p, 1L, Long::plus)
                remainder--
            }
        }
        return raw
    }
}
