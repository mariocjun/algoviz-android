// SplitwiseActivity — "Racha": split a bill among friends and settle up with the
// fewest payments. The expense ledger + greedy debt simplification live in the
// NDK-free Splitwise.kt (host-unit-tested); this is just the (dark, app-native)
// UI on top. Aims to look nicer than the original Splitwise: zero-centred
// diverging balance bars, an animated minimal-payment plan, and the algorithmic
// flex — "we simplified N debts into M payments".
package com.mariocjun.algoviz

import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

// ---- Palette (shared look with the rest of the app) ---------------------------

private val BG = Color(0xFF161618)
private val PANEL = Color(0xFF202023)
private val PANEL_HI = Color(0xFF2A2A2E)
private val LINE = Color(0xFF3A3A40)
private val TXT = Color(0xFFF2F2F4)
private val TXT_DIM = Color(0xFF9A9AA2)
private val BLUE = Color(0xFF4296FA)
private val OWED = Color(0xFF3DCF7A)   // creditor (is owed) — green
private val OWES = Color(0xFFF2554B)   // debtor (owes) — coral

// Distinct per-person avatar hues.
private val PERSON_HUES = listOf(
    Color(0xFF4296FA), Color(0xFF9B5DE5), Color(0xFFE08A2E), Color(0xFF2BB6C4),
    Color(0xFFE05299), Color(0xFF35C46B), Color(0xFFE8B62E), Color(0xFFF2554B),
)

private fun money(cents: Long): String =
    "R$ " + String.format(Locale("pt", "BR"), "%.2f", cents / 100.0)

private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

class SplitwiseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BG, surface = PANEL, primary = BLUE, onPrimary = Color.White,
                    onBackground = TXT, onSurface = TXT,
                ),
            ) {
                Surface(color = BG) { Box(Modifier.safeDrawingPadding()) { SplitScreen() } }
            }
        }
    }
}

@Composable
private fun SplitScreen() {
    val view = LocalView.current
    val people = remember { mutableStateListOf("Mário", "Bia", "Caio", "Duda") }
    val expenses = remember {
        mutableStateListOf(
            Expense("Mário", 12000, SplitMode.Equal(listOf("Mário", "Bia", "Caio", "Duda")), "Churrasco"),
            Expense("Bia", 6000, SplitMode.Equal(listOf("Bia", "Caio", "Duda")), "Cerveja"),
            Expense("Caio", 4500, SplitMode.Equal(listOf("Mário", "Caio")), "Uber"),
        )
    }

    // Derived (cheap to recompute for a handful of expenses).
    val ledger = Ledger().apply { expenses.forEach { addExpense(it) } }
    val rawBal = ledger.balances()
    val balances = people.associateWith { rawBal[it] ?: 0L }
    val settlements = ledger.settlements()
    val total = expenses.sumOf { it.amountCents }
    // Raw distinct debtor→creditor pairs (Equal splits) — the "before" count.
    val rawPairs = buildSet {
        for (e in expenses) (e.split as? SplitMode.Equal)?.participants
            ?.filter { it != e.payer }?.forEach { add(it to e.payer) }
    }.size

    val colorOf: (String) -> Color = { name ->
        PERSON_HUES[(people.indexOf(name).coerceAtLeast(0)) % PERSON_HUES.size]
    }
    val onAdd: (Expense) -> Unit = { e ->
        expenses.add(e)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    val onAddPerson: (String) -> Unit = { name ->
        if (name.isNotBlank() && people.none { it.equals(name.trim(), true) }) people.add(name.trim())
    }

    var tab by remember { mutableIntStateOf(0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(if (landscape) 4.dp else 8.dp))
        Text("Racha",
            style = if (landscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = TXT)
        if (!landscape) Text("divide a conta · simplifica as dívidas",
            style = MaterialTheme.typography.bodyMedium, color = TXT_DIM)
        Spacer(Modifier.height(if (landscape) 8.dp else 12.dp))
        SplitTabs(tab) { tab = it }
        Spacer(Modifier.height(if (landscape) 8.dp else 12.dp))

        when (tab) {
            0 -> CenteredScroll {
                SummaryCard(total, expenses.size, rawPairs, settlements.size)
                Spacer(Modifier.height(14.dp))
                BalancesSection(people, balances, colorOf)
                Spacer(Modifier.height(14.dp))
                AddExpenseCard(people, colorOf, onAdd, onAddPerson)
                Spacer(Modifier.height(16.dp))
            }
            1 -> CenteredScroll {
                SummaryCard(total, expenses.size, rawPairs, settlements.size)
                Spacer(Modifier.height(14.dp))
                SettlementsSection(settlements, colorOf)
                Spacer(Modifier.height(16.dp))
            }
            else -> DebtGraph(people, expenses.toList(), settlements, colorOf, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CenteredScroll(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
private fun SplitTabs(selected: Int, onPick: (Int) -> Unit) {
    val labels = listOf("Saldos", "Acerto", "Grafo")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            val bg by animateColorAsState(if (on) BLUE else Color.Transparent, tween(220), label = "tab")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg)
                    .clickable { onPick(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (on) Color.White else TXT_DIM,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---- Summary ------------------------------------------------------------------

@Composable
private fun SummaryCard(total: Long, expenseCount: Int, rawPairs: Int, payments: Int) {
    Surface(color = PANEL, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Total na vaquinha", color = TXT_DIM, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            Text(money(total), color = TXT, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("${expenseCount} despesas", TXT_DIM)
                Spacer(Modifier.width(8.dp))
                if (payments == 0) {
                    Pill("tá tudo certo", OWED)
                } else if (rawPairs > payments) {
                    Pill("$rawPairs dívidas → $payments pagamentos", BLUE)
                } else {
                    Pill("$payments pagamentos pra zerar", BLUE)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) }
}

// ---- Balances (diverging bars) ------------------------------------------------

@Composable
private fun BalancesSection(people: List<String>, balances: Map<String, Long>, colorOf: (String) -> Color) {
    val maxAbs = (balances.values.maxOfOrNull { abs(it) } ?: 1L).coerceAtLeast(1L)
    SectionTitle("Saldos")
    Surface(color = PANEL, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            for (name in people) {
                BalanceRow(name, balances[name] ?: 0L, maxAbs, colorOf(name))
            }
        }
    }
}

@Composable
private fun BalanceRow(name: String, bal: Long, maxAbs: Long, color: Color) {
    val frac = (bal.toFloat() / maxAbs.toFloat()).coerceIn(-1f, 1f)
    val animFrac by animateFloatAsState(frac, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "bal")
    val valColor = when {
        bal > 0 -> OWED
        bal < 0 -> OWES
        else -> TXT_DIM
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, color, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = TXT, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    when {
                        bal > 0 -> "recebe ${money(bal)}"
                        bal < 0 -> "deve ${money(-bal)}"
                        else -> "quitado"
                    },
                    color = valColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(6.dp))
            // zero-centred diverging bar: track + centre line + half-anchored fill
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(PANEL_HI)) {
                Box(Modifier.align(Alignment.Center).width(1.5.dp).fillMaxHeight().background(LINE))
                DivergingFill(animFrac, OWED, OWES)
            }
        }
    }
}

/** Fills from the centre toward the right (positive) or left (negative). */
@Composable
private fun DivergingFill(frac: Float, pos: Color, neg: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp)) {
        if (frac >= 0f) {
            // right half, fill grows from the centre rightward
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).height(8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(50)).background(pos))
            }
        } else {
            // left half, fill grows from the centre leftward
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).height(8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.fillMaxWidth((-frac).coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(50)).background(neg))
            }
        }
    }
}

// ---- Settlements --------------------------------------------------------------

@Composable
private fun SettlementsSection(settlements: List<Settlement>, colorOf: (String) -> Color) {
    SectionTitle("Quem paga quem")
    if (settlements.isEmpty()) {
        AllSettledCard()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settlements.forEachIndexed { i, s -> SettlementCard(s, colorOf, i) }
    }
}

@Composable
private fun SettlementCard(s: Settlement, colorOf: (String) -> Color, index: Int) {
    var shown by remember(s) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(s) {
        kotlinx.coroutines.delay(index * 70L); shown = true
    }
    AnimatedVisibility(visible = shown, enter = fadeIn(tween(260)) + expandVertically(tween(260))) {
        Surface(color = PANEL, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(s.from, colorOf(s.from), 34.dp)
                Spacer(Modifier.width(8.dp))
                Text(s.from, color = TXT, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "paga", tint = TXT_DIM,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Avatar(s.to, colorOf(s.to), 34.dp)
                Spacer(Modifier.width(8.dp))
                Text(s.to, color = TXT, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(money(s.amountCents), color = OWED, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun AllSettledCard() {
    val pulse by rememberInfiniteTransition(label = "settled").animateFloat(
        0.25f, 0.6f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g",
    )
    Surface(color = OWED.copy(alpha = 0.10f + pulse * 0.06f), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎉", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text("Tá tudo certo!", color = OWED, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Text("ninguém deve nada", color = TXT_DIM, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- Add expense --------------------------------------------------------------

@Composable
private fun AddExpenseCard(
    people: List<String>, colorOf: (String) -> Color,
    onAdd: (Expense) -> Unit, onAddPerson: (String) -> Unit,
) {
    var payer by remember { mutableStateOf(people.firstOrNull() ?: "") }
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val among = remember { mutableStateListOf<String>().apply { addAll(people) } }
    var newPerson by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    // keep `payer`/`among` valid as people changes
    if (payer !in people) payer = people.firstOrNull() ?: ""

    SectionTitle("Nova despesa")
    Surface(color = PANEL, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Quem pagou", color = TXT_DIM, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in people) ChoiceChip(p, p == payer, colorOf(p)) { payer = p }
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkField(amount, { amount = it; err = null }, "Valor (R$)", KeyboardType.Decimal, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                DarkField(desc, { desc = it }, "Descrição", KeyboardType.Text, Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(14.dp))

            Text("Dividir entre", color = TXT_DIM, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in people) {
                    val on = p in among
                    ChoiceChip(p, on, colorOf(p)) { if (on) among.remove(p) else among.add(p) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // live per-head preview
            val cents = parseCents(amount)
            if (cents > 0 && among.isNotEmpty()) {
                Text("≈ ${money(cents / among.size)} por pessoa (${among.size})",
                    color = TXT_DIM, style = MaterialTheme.typography.labelMedium)
            }
            err?.let { Spacer(Modifier.height(4.dp)); Text(it, color = OWES, style = MaterialTheme.typography.labelMedium) }

            Spacer(Modifier.height(14.dp))
            PrimaryWide("Adicionar despesa", BLUE) {
                val c = parseCents(amount)
                when {
                    c <= 0 -> err = "Informe um valor válido"
                    among.isEmpty() -> err = "Escolha pelo menos uma pessoa"
                    payer.isEmpty() -> err = "Escolha quem pagou"
                    else -> {
                        onAdd(Expense(payer, c, SplitMode.Equal(among.toList()), desc.trim()))
                        amount = ""; desc = ""; err = null
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkField(newPerson, { newPerson = it }, "Adicionar pessoa", KeyboardType.Text, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(PANEL_HI)
                        .clickable {
                            onAddPerson(newPerson)
                            if (newPerson.isNotBlank()) among.add(newPerson.trim())
                            newPerson = ""
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "Adicionar pessoa", tint = TXT) }
            }
        }
    }
}

private fun parseCents(s: String): Long {
    val cleaned = s.trim().replace("R$", "").replace(" ", "").replace(",", ".")
    val v = cleaned.toDoubleOrNull() ?: return 0L
    return (v * 100.0).roundToLong()
}

// ---- Small shared pieces ------------------------------------------------------

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = TXT, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Avatar(name: String, color: Color, dp: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "chip")
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) color else PANEL_HI)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected) Color.White else TXT_DIM,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DarkField(
    value: String, onChange: (String) -> Unit, label: String,
    kbd: KeyboardType, modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kbd),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BLUE, unfocusedBorderColor = LINE,
            focusedLabelColor = BLUE, unfocusedLabelColor = TXT_DIM,
            focusedTextColor = TXT, unfocusedTextColor = TXT,
            cursorColor = BLUE,
        ),
    )
}

@Composable
private fun PrimaryWide(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(color).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

// ---- Debt graph (min-cash-flow) — the 3rd tab --------------------------------

@Composable
private fun DebtGraph(
    people: List<String>, expenses: List<Expense>, settlements: List<Settlement>,
    colorOf: (String) -> Color, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    // Direct debts (the "before"): each non-payer owes their Equal share to the payer.
    val direct = LinkedHashMap<Pair<String, String>, Long>().apply {
        for (e in expenses) {
            val parts = (e.split as? SplitMode.Equal)?.participants ?: continue
            val n = parts.size; if (n == 0) continue
            val base = e.amountCents / n; val rem = (e.amountCents - base * n).toInt()
            parts.forEachIndexed { i, p ->
                val owed = base + if (i < rem) 1L else 0L
                if (p != e.payer && owed > 0) merge(p to e.payer, owed, Long::plus)
            }
        }
    }
    val maxAmt = ((direct.values.maxOrNull() ?: 0L)
        .coerceAtLeast(settlements.maxOfOrNull { it.amountCents } ?: 0L)).coerceAtLeast(1L)

    var simplified by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (simplified) 1f else 0f, tween(900), label = "collapse")
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(450); simplified = true }
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        0f, (2.0 * PI).toFloat(), infiniteRepeatable(tween(3800), RepeatMode.Restart), label = "bobv")

    // The graph IS the content: it fills the whole tab; the headline + toggle float
    // on top so chrome never out-sizes the content (esp. in landscape).
    Box(modifier) {
        Surface(color = PANEL, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                val n = people.size
                if (n == 0) return@Canvas
                val topInset = 70f; val bottomInset = 104f         // clear the floating headline + toggle
                val cx = size.width / 2f
                val cy = (topInset + (size.height - bottomInset)) / 2f
                val rx = (size.width / 2f - 52f).coerceAtLeast(10f)
                val ry = ((size.height - topInset - bottomInset) / 2f - 6f).coerceAtLeast(10f)
                val nodeR = 21f
                val pos = HashMap<String, Offset>(n)
                people.forEachIndexed { i, name ->
                    val a = -PI / 2.0 + i * 2.0 * PI / n
                    val bobY = sin(bob + i.toFloat()) * 4f          // gentle life on the nodes
                    pos[name] = Offset(cx + (rx * cos(a)).toFloat(), cy + (ry * sin(a)).toFloat() + bobY)
                }
                // Direto = the tangle (arrows only, no labels); Simplificado = clean
                // arrows WITH amount chips. A label never sits bare on a line.
                for ((pair, amt) in direct) {
                    val pu = pos[pair.first] ?: continue
                    val pv = pos[pair.second] ?: continue
                    drawDebtEdge(measurer, pu, pv, amt, (1f - p) * 0.85f, colorOf(pair.first), nodeR, maxAmt, label = false)
                }
                for (st in settlements) {
                    val pu = pos[st.from] ?: continue
                    val pv = pos[st.to] ?: continue
                    drawDebtEdge(measurer, pu, pv, st.amountCents, p, colorOf(st.from), nodeR, maxAmt, label = true)
                }
                for (name in people) {
                    val pp = pos[name] ?: continue
                    drawCircle(colorOf(name), radius = nodeR, center = pp)
                    val ist = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val im = measurer.measure(initials(name), ist)
                    drawText(measurer, initials(name),
                        topLeft = Offset(pp.x - im.size.width / 2f, pp.y - im.size.height / 2f), style = ist)
                    val nst = TextStyle(color = TXT, fontSize = 10.sp)
                    val nm = measurer.measure(name, nst)
                    drawText(measurer, name,
                        topLeft = Offset(pp.x - nm.size.width / 2f, pp.y + nodeR + 3f), style = nst)
                }
            }
        }
        // compact floating headline (top-start)
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("${direct.size} diretas", OWES)
            Spacer(Modifier.width(6.dp))
            Text("→", color = TXT_DIM, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Pill("${settlements.size} pagam.", OWED)
        }
        // floating toggle (bottom-center)
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                .clip(RoundedCornerShape(13.dp)).background(BG.copy(alpha = 0.85f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GraphToggle("Direto", !simplified) { simplified = false }
            GraphToggle("Simplificado", simplified) { simplified = true }
        }
    }
}

@Composable
private fun GraphToggle(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) BLUE else Color.Transparent, tween(200), label = "gt")
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { onClick() }
        .padding(horizontal = 18.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) Color.White else TXT_DIM,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge)
    }
}

/** One directed debt edge (debtor → creditor): line thickness ∝ amount, arrowhead
 *  at the creditor. When `label`, the amount rides an OPAQUE chip off the line
 *  (~62% toward the creditor) so it never reads as text sitting on a line. */
private fun DrawScope.drawDebtEdge(
    measurer: TextMeasurer, pu: Offset, pv: Offset, amt: Long, alpha: Float, col: Color,
    nodeR: Float, maxAmt: Long, label: Boolean,
) {
    if (alpha <= 0.02f) return
    val dx = pv.x - pu.x; val dy = pv.y - pu.y
    val len = hypot(dx, dy); if (len < 1f) return
    val ux = dx / len; val uy = dy / len
    val s = Offset(pu.x + ux * nodeR, pu.y + uy * nodeR)
    val e = Offset(pv.x - ux * (nodeR + 11f), pv.y - uy * (nodeR + 11f))
    val th = (2.5f + 7f * (amt.toFloat() / maxAmt)).coerceIn(2f, 10f)
    drawLine(col.copy(alpha = alpha), s, e, strokeWidth = th)
    val ah = 12f; val px = -uy; val py = ux
    val b1 = Offset(e.x - ux * ah + px * ah * 0.55f, e.y - uy * ah + py * ah * 0.55f)
    val b2 = Offset(e.x - ux * ah - px * ah * 0.55f, e.y - uy * ah - py * ah * 0.55f)
    drawPath(Path().apply { moveTo(e.x, e.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() },
        col.copy(alpha = alpha))
    if (label && alpha > 0.5f) {
        val t = 0.62f
        val mx = s.x + (e.x - s.x) * t + px * 11f
        val my = s.y + (e.y - s.y) * t + py * 11f
        val style = TextStyle(color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val m = measurer.measure(money(amt), style)
        val cw = m.size.width + 12f; val ch = m.size.height + 6f
        val tl = Offset(mx - cw / 2f, my - ch / 2f)
        drawRoundRect(BG.copy(alpha = 0.92f), topLeft = tl, size = Size(cw, ch), cornerRadius = CornerRadius(7f, 7f))
        drawRoundRect(col.copy(alpha = 0.45f), topLeft = tl, size = Size(cw, ch),
            cornerRadius = CornerRadius(7f, 7f), style = Stroke(1f))
        drawText(measurer, money(amt), topLeft = Offset(mx - m.size.width / 2f, my - m.size.height / 2f), style = style)
    }
}
