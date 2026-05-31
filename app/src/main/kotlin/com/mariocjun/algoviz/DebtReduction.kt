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

    /** Phase 1 (Enchendo) — reveal one raw debt as an edge. No netting yet, so
     *  `balAfter` is all zero: this phase just builds the problem on screen. */
    data class Build(
        val edge: DirectDebt,
        override val balAfter: Map<Person, Long>,
    ) : ReduceStep()

    /** Phase 2 (Podando) — fold one raw debt into the running net balances (its
     *  edge then disappears from the tangle). */
    data class Absorb(
        val edge: DirectDebt,
        override val balAfter: Map<Person, Long>,
    ) : ReduceStep()

    /** Phase 3 (Acerto) — one greedy settlement: the biggest creditor is paid by
     *  the biggest debtor, `amountCents = min(creditorBefore, -debtorBefore)`. */
    data class Settle(
        val from: Person, val to: Person, val amountCents: Long,
        val creditorBefore: Long, val debtorBefore: Long,
        override val balAfter: Map<Person, Long>,
    ) : ReduceStep()
}

/** The full reduction of one ledger: the raw debts, the minimal plan, and the
 *  ordered steps that morph one into the other. Steps run in three phases:
 *  Build (one per debt, the graph fills up) → Absorb (one per debt, the graph
 *  nets into balances) → Settle (the greedy payments). */
class DebtReduction(
    val people: List<Person>,
    val direct: List<DirectDebt>,
    val settlements: List<Settlement>,
    val steps: List<ReduceStep>,
) {
    val buildCount: Int get() = direct.size          // phase 1 length (debts appear)
    val pruneCount: Int get() = direct.size          // phase 2 length (debts net away)
    val buildEnd: Int get() = buildCount             // cursor when the graph is full
    val pruneEnd: Int get() = buildCount + pruneCount // cursor when only balances remain

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
    val zero: Map<Person, Long> = people.associateWith { 0L }

    // Phase 1 (Enchendo) — reveal each debt as an edge; balances stay zero.
    for (d in direct) steps.add(ReduceStep.Build(d, zero))

    // Phase 2 (Podando) — absorb each raw debt into the running net balances.
    val running = LinkedHashMap<Person, Long>().apply { people.forEach { put(it, 0L) } }
    for (d in direct) {
        running.merge(d.from, -d.amountCents, Long::plus)
        running.merge(d.to, d.amountCents, Long::plus)
        steps.add(ReduceStep.Absorb(d, LinkedHashMap(running)))
    }

    // Phase 3 (Acerto) — greedy settle on the net balances. Same rule (and the
    // same value-then-id tie-break) as Ledger.settlements(), so the plans agree.
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

/** The extreme demo: the COMPLETE debt graph on 20 people — every one of the
 *  C(20,2) = 190 unique pairs owes, the maximum possible ("extremo") — yet it
 *  all nets to a SINGLE payment: Mário deve R$67,00 a Cássia.
 *
 *  Why it needs a construction (not just rings): with 20 vertices each of odd
 *  degree 19, no uniform-amount orientation can zero 18 people's net. So we lay
 *  a uniform base debt on all 190 pairs (i owes j) and apply one path-flow
 *  correction along 0–1–…–19 that retargets the net to b = {Mário −67, Cássia
 *  +67, rest 0} while keeping every one of the 190 debts non-zero. The greedy
 *  plan on that net is exactly [Mário → Cássia, 6700]. Host-test-verified. */
fun extremeDemo(): Pair<List<String>, List<Expense>> {
    val p = listOf(
        "Mário", "Cássia", "Bia", "Caio", "Duda", "Ana", "Beto", "Lia", "Téo", "Rafa",
        "Nina", "Gus", "Lara", "Ivo", "Sofia", "João", "Manu", "Léo", "Cleo", "Vini",
    )
    val n = p.size                                   // 20
    val b = LongArray(n).also { it[0] = -6700L; it[1] = 6700L }   // Mário owes Cássia R$67
    // Uniform base: for every pair i<j, i owes j BASE. Net from that base is the
    // gradient g_k = BASE*(2k-(n-1)); f_k = prefix sum of (b_k - g_k) is the flow
    // the path edge {k,k+1} must carry, so it becomes "i owes j (BASE - f_k)".
    val BASE = 137L
    val pathSigned = LongArray(n - 1)
    var f = 0L
    for (k in 0 until n - 1) {
        f += b[k] - BASE * (2L * k - (n - 1))
        pathSigned[k] = BASE - f                     // signed: + = i owes j, - = j owes i
    }
    val ex = buildList {
        for (i in 0 until n) for (j in i + 1 until n) {
            val signed = if (j == i + 1) pathSigned[i] else BASE
            val debtor: Int; val creditor: Int; val amt: Long
            if (signed >= 0) { debtor = i; creditor = j; amt = signed }
            else { debtor = j; creditor = i; amt = -signed }
            // 2-person Equal expense: the creditor "paid" 2*amt for {creditor, debtor},
            // so the debtor owes the creditor exactly `amt` — one directed debt per pair.
            add(Expense(p[creditor], 2L * amt, SplitMode.Equal(listOf(p[creditor], p[debtor])), "x"))
        }
    }
    return p to ex
}
