// Host-JVM unit tests for the stepped debt reduction that powers the Extreme
// visualizer. Pins the headline scenario (41 direct debts → a single payment,
// Mário deve R$67,00 a Cássia) and proves the stepped plan agrees with the
// plain Ledger.settlements(). Runs via `./gradlew test`.
package com.mariocjun.algoviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtReductionTest {

    @Test fun extremeCollapsesToSinglePayment() {
        val (people, expenses) = extremeDemo()
        val r = buildReduction(people, expenses)

        // 20 people in two cancelling rings + one cross debt → 41 distinct edges.
        assertEquals(41, r.direct.size)

        // The whole tangle nets to exactly one payment: Mário → Cássia, R$67,00.
        assertEquals(listOf(Settlement("Mário", "Cássia", 6700)), r.settlements)

        // Steps = one Absorb per direct debt, then one Settle.
        assertEquals(42, r.steps.size)
        assertTrue(r.steps.take(41).all { it is ReduceStep.Absorb })
        val last = r.steps.last()
        assertTrue(last is ReduceStep.Settle)
        last as ReduceStep.Settle
        assertEquals("Mário", last.from)
        assertEquals("Cássia", last.to)
        assertEquals(6700L, last.amountCents)
        assertEquals(6700L, last.creditorBefore)
        assertEquals(-6700L, last.debtorBefore)

        // Everyone is square once the plan is applied.
        assertTrue(r.balancesAt(r.steps.size).values.all { it == 0L })
    }

    @Test fun absorbingAllDirectDebtsReproducesLedgerBalances() {
        val (people, expenses) = extremeDemo()
        val r = buildReduction(people, expenses)
        val ledgerBal = Ledger().apply { expenses.forEach { addExpense(it) } }.balances()
        val absorbed = r.balancesAt(r.absorbCount)
        for (p in people) assertEquals("balance of $p", ledgerBal[p], absorbed[p])
    }

    @Test fun cursorZeroIsAllZero() {
        val (people, expenses) = extremeDemo()
        val r = buildReduction(people, expenses)
        assertTrue(r.balancesAt(0).values.all { it == 0L })
    }

    @Test fun steppedPlanMatchesLedgerOnEqualSplits() {
        // A small Equal-only ledger: the stepped reduction must produce the same
        // plan (and order) as the plain greedy.
        val people = listOf("alice", "bob", "carol")
        val expenses = listOf(
            Expense("alice", 300, SplitMode.Equal(listOf("alice", "bob", "carol"))),
            Expense("bob", 200, SplitMode.Equal(listOf("bob", "carol"))),
        )
        val stepped = buildReduction(people, expenses).settlements
        val plain = Ledger().apply { expenses.forEach { addExpense(it) } }.settlements()
        assertEquals(plain, stepped)
    }

    @Test fun directDebtsMergeRepeatedPairs() {
        // Two expenses creating the same debtor→creditor pair merge into one edge.
        val expenses = listOf(
            Expense("alice", 1000, SplitMode.Equal(listOf("alice", "bob"))),  // bob owes alice 500
            Expense("alice", 400, SplitMode.Equal(listOf("alice", "bob"))),   // bob owes alice 200
        )
        val d = directDebts(expenses)
        assertEquals(1, d.size)
        assertEquals(DirectDebt("bob", "alice", 700), d[0])
    }
}
