// DebtReduction — step-by-step debt simplification, built for the Extreme
// visualizer. Pure Kotlin (no Android), host-unit-tested. It mirrors
// Ledger.settlements() exactly but records two things the plain result throws
// away: every raw debt as it is absorbed into the running net balances, and
// every greedy pick. That lets the UI step, rewind, and animate the collapse
// the same way the sort visualizer steps through a sort — one mutation per frame.
//
// Scope: the direct-debt decomposition only understands Equal splits (the
// Extreme demo is Equal-only). For an Equal-only ledger the absorbed balances
// equal Ledger.balances() exactly, so the greedy plan equals Ledger.settlements().
package com.mariocjun.algoviz

/** A raw directed debt (`from` owes `to`) before any simplification. */
data class DirectDebt(val from: Person, val to: Person, val amountCents: Long)

/** One reduction step. The UI plays these forward/back like sort steps; each
 *  carries the full net-balance snapshot after it, so rewinding is a lookup. */
sealed class ReduceStep {
    /** Net balance per person after this step (signed cents; + = creditor). */
    abstract val balAfter: Map<Person, Long>

    /** Act 1 — fold one raw debt into the running net balances (its edge then
     *  disappears from the tangle). */
    data class Absorb(
        val edge: DirectDebt,
        override val balAfter: Map<Person, Long>,
    ) : ReduceStep()

    /** Act 2 — one greedy settlement: the biggest creditor is paid by the
     *  biggest debtor, `amountCents = min(creditorBefore, -debtorBefore)`. */
    data class Settle(
        val from: Person, val to: Person, val amountCents: Long,
        val creditorBefore: Long, val debtorBefore: Long,
        override val balAfter: Map<Person, Long>,
    ) : ReduceStep()
}

/** The full reduction of one ledger: the raw debts, the minimal plan, and the
 *  ordered steps that morph one into the other (all Absorb steps, then all
 *  Settle steps). */
class DebtReduction(
    val people: List<Person>,
    val direct: List<DirectDebt>,
    val settlements: List<Settlement>,
    val steps: List<ReduceStep>,
) {
    /** steps[0 until absorbCount] are Absorb; the rest are Settle. */
    val absorbCount: Int get() = direct.size

    /** Net balances after `cursor` steps (cursor 0 = the all-zero start). */
    fun balancesAt(cursor: Int): Map<Person, Long> =
        if (cursor <= 0) people.associateWith { 0L }
        else steps[cursor.coerceAtMost(steps.size) - 1].balAfter
}

/** Direct debts from a ledger's Equal-split expenses: each non-payer owes their
 *  rounded share to the payer; debts between the same ordered pair merge. This
 *  mirrors the "Direto" view's edge set (insertion order preserved). */
fun directDebts(expenses: List<Expense>): List<DirectDebt> {
    val acc = LinkedHashMap<Pair<Person, Person>, Long>()
    for (e in expenses) {
        val parts = (e.split as? SplitMode.Equal)?.participants ?: continue
        val n = parts.size
        if (n == 0) continue
        val base = e.amountCents / n
        val rem = (e.amountCents - base * n).toInt()      // 0 .. n-1
        parts.forEachIndexed { i, p ->
            val owed = base + if (i < rem) 1L else 0L
            if (p != e.payer && owed > 0) acc.merge(p to e.payer, owed, Long::plus)
        }
    }
    return acc.map { (pair, amt) -> DirectDebt(pair.first, pair.second, amt) }
}

/** Build the stepped reduction for a ledger. */
fun buildReduction(people: List<Person>, expenses: List<Expense>): DebtReduction {
    val direct = directDebts(expenses)
    val steps = mutableListOf<ReduceStep>()

    // Act 1 — absorb each raw debt into the running net balances.
    val running = LinkedHashMap<Person, Long>().apply { people.forEach { put(it, 0L) } }
    for (d in direct) {
        running.merge(d.from, -d.amountCents, Long::plus)
        running.merge(d.to, d.amountCents, Long::plus)
        steps.add(ReduceStep.Absorb(d, LinkedHashMap(running)))
    }

    // Act 2 — greedy settle on the net balances. Same rule (and the same
    // value-then-id tie-break) as Ledger.settlements(), so the plans agree.
    val bal = LinkedHashMap(running)
    val settlements = mutableListOf<Settlement>()
    while (true) {
        val creditor = bal.entries.filter { it.value > 0 }
            .maxWithOrNull(compareBy({ it.value }, { it.key })) ?: break
        val debtor = bal.entries.filter { it.value < 0 }
            .minWithOrNull(compareBy({ it.value }, { it.key })) ?: break
        val pay = minOf(creditor.value, -debtor.value)
        if (pay <= 0) break
        val cKey = creditor.key; val cBefore = creditor.value
        val dKey = debtor.key; val dBefore = debtor.value
        settlements.add(Settlement(from = dKey, to = cKey, amountCents = pay))
        bal[cKey] = cBefore - pay
        bal[dKey] = dBefore + pay
        steps.add(ReduceStep.Settle(dKey, cKey, pay, cBefore, dBefore, LinkedHashMap(bal)))
    }

    return DebtReduction(people, direct, settlements, steps)
}

/** The extreme demo: 20 people in two debt rings that fully cancel + one cross
 *  debt, so 41 direct debts (20 owe 20) collapse to a SINGLE payment —
 *  Mário deve R$67,00 a Cássia. Script- and host-test-verified. Shared by the
 *  Racha graph (static) and the Extreme visualizer (animated). */
fun extremeDemo(): Pair<List<String>, List<Expense>> {
    val p = listOf(
        "Mário", "Cássia", "Bia", "Caio", "Duda", "Ana", "Beto", "Lia", "Téo", "Rafa",
        "Nina", "Gus", "Lara", "Ivo", "Sofia", "João", "Manu", "Léo", "Cleo", "Vini",
    )
    val ex = buildList {
        for (i in 0 until 20) {
            add(Expense(p[i], 2000, SplitMode.Equal(listOf(p[i], p[(i + 1) % 20])), "anel"))
            add(Expense(p[i], 2000, SplitMode.Equal(listOf(p[i], p[(i + 2) % 20])), "anel2"))
        }
        // Cássia pays R$134 for {Cássia, Mário} → each owes R$67 → net: Mário owes Cássia R$67.
        add(Expense("Cássia", 13400, SplitMode.Equal(listOf("Cássia", "Mário")), "viagem"))
    }
    return p to ex
}
