// Host-JVM unit tests for the Splitwise ledger. No Android dependency — these
// run via `./gradlew test`. Covers each split mode, the remainder-distribution
// invariant (shares always sum back to the expense), and the greedy debt
// simplification on a few canonical multi-person cases.
package com.mariocjun.algoviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitwiseTest {

    // ---- Splits ------------------------------------------------------------

    @Test fun equalSplitDistributesRemainderInOrder() {
        val l = Ledger()
        // 1001 cents split 3 ways: 334 / 334 / 333 (first one gets the spare).
        l.addExpense(Expense("alice", 1001, SplitMode.Equal(listOf("alice", "bob", "carol"))))
        val b = l.balances()
        assertEquals(1001L - 334L, b["alice"])    // paid 1001, owes 334
        assertEquals(-334L, b["bob"])
        assertEquals(-333L, b["carol"])
        assertEquals(0L, b.values.sum())        // closed system
    }

    @Test fun exactSplitRequiresMatchingSum() {
        val l = Ledger()
        try {
            l.addExpense(Expense("alice", 1000,
                SplitMode.Exact(mapOf("alice" to 400L, "bob" to 500L))))
            fail("should have thrown — shares 900 != amount 1000")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun percentSplitMustSumTo100() {
        val l = Ledger()
        try {
            l.addExpense(Expense("alice", 1000,
                SplitMode.Percent(mapOf("alice" to 40.0, "bob" to 50.0))))
            fail("should have thrown — 40+50 != 100")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun percentSplitDistributesRemainder() {
        val l = Ledger()
        // 1000 split 33/33/34 — 330/330/340 (sum 1000, no remainder cents needed).
        l.addExpense(Expense("alice", 1000,
            SplitMode.Percent(mapOf("alice" to 33.0, "bob" to 33.0, "carol" to 34.0))))
        val b = l.balances()
        assertEquals(1000L - 330L, b["alice"])
        assertEquals(-330L, b["bob"])
        assertEquals(-340L, b["carol"])
        assertEquals(0L, b.values.sum())
    }

    @Test fun sharesSplit1to2() {
        val l = Ledger()
        // 300 split 1:2 → 100 / 200.
        l.addExpense(Expense("alice", 300,
            SplitMode.Shares(mapOf("alice" to 1, "bob" to 2))))
        val b = l.balances()
        assertEquals(300L - 100L, b["alice"])
        assertEquals(-200L, b["bob"])
    }

    @Test fun sharesRemainderGoesToFirstParticipants() {
        val l = Ledger()
        // 10 cents split 1:1:1 → floor 3/3/3 = 9 raw, +1 remainder cent on alice.
        l.addExpense(Expense("alice", 10,
            SplitMode.Shares(linkedMapOf("alice" to 1, "bob" to 1, "carol" to 1))))
        val b = l.balances()
        assertEquals(10L - 4L, b["alice"])    // alice paid 10, owes 4
        assertEquals(-3L, b["bob"])
        assertEquals(-3L, b["carol"])
        assertEquals(0L, b.values.sum())
    }

    // ---- Balances close ----------------------------------------------------

    @Test fun balancesAlwaysSumToZero() {
        val l = Ledger()
        l.addExpense(Expense("alice", 1500, SplitMode.Equal(listOf("alice", "bob", "carol"))))
        l.addExpense(Expense("bob", 700, SplitMode.Equal(listOf("alice", "bob"))))
        l.addExpense(Expense("carol", 333,
            SplitMode.Shares(mapOf("alice" to 1, "bob" to 2, "carol" to 1))))
        assertEquals(0L, l.balances().values.sum())
    }

    // ---- Settlements -------------------------------------------------------

    @Test fun trivialPairSettlement() {
        val l = Ledger()
        l.addExpense(Expense("alice", 1000, SplitMode.Equal(listOf("alice", "bob"))))
        val s = l.settlements()
        assertEquals(1, s.size)
        assertEquals(Settlement("bob", "alice", 500), s[0])
    }

    @Test fun chainPaymentCollapsesToOneEdge() {
        // Classic A→B→C: each owes the next 100. Net: A pays 100 to C, B even.
        val l = Ledger()
        l.addExpense(Expense("alice", 200, SplitMode.Exact(mapOf("alice" to 100L, "bob" to 100L))))
        l.addExpense(Expense("bob",   200, SplitMode.Exact(mapOf("bob"   to 100L, "carol" to 100L))))
        val s = l.settlements()
        assertEquals(1, s.size)
        assertEquals("carol", s[0].from)
        assertEquals("alice", s[0].to)
        assertEquals(100L, s[0].amountCents)
    }

    @Test fun settlementsClearBalances() {
        // Heterogeneous multi-person ledger — greedy should still leave everyone
        // within 1 cent of zero (and in fact zero for clean inputs).
        val l = Ledger()
        l.addExpense(Expense("alice", 6000, SplitMode.Equal(listOf("alice", "bob", "carol", "dave"))))
        l.addExpense(Expense("bob",   4000, SplitMode.Equal(listOf("alice", "bob"))))
        l.addExpense(Expense("carol", 1200,
            SplitMode.Shares(mapOf("carol" to 1, "dave" to 2))))

        val balBefore = l.balances().toMutableMap()
        for (s in l.settlements()) {
            balBefore.merge(s.from, s.amountCents, Long::plus)
            balBefore.merge(s.to, -s.amountCents, Long::plus)
        }
        // Each net must close, sign preserved across all participants.
        for ((p, v) in balBefore) assertTrue("$p left with $v", kotlin.math.abs(v) <= 1)
    }

    @Test fun settlementsAreDeterministic() {
        // Same ledger built twice in different insertion orders → same plan,
        // because settlements() ties on the person id alphabetically.
        val a = Ledger().apply {
            addExpense(Expense("alice", 1000, SplitMode.Equal(listOf("alice", "bob"))))
            addExpense(Expense("bob",   500,  SplitMode.Equal(listOf("alice", "bob"))))
        }
        val b = Ledger().apply {
            addExpense(Expense("bob",   500,  SplitMode.Equal(listOf("bob", "alice"))))
            addExpense(Expense("alice", 1000, SplitMode.Equal(listOf("bob", "alice"))))
        }
        assertEquals(a.settlements(), b.settlements())
    }
}
