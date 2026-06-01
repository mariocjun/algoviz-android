// ExtremeTutorial — the "modo de usar" / motivation story for the Min Cash Flow
// mini-app. It opens automatically the first time the app is opened in a session
// (X to close, "não mostrar de novo" valid for that execution only), and can be
// reopened any time via the ℹ button. Style matches the rest of the Extreme:
// dark glass, the app palette. Tells the motivation 3Blue1Brown-style — an
// interactive money-throwing figure, the C(n,2) framing, then the greedy idea
// (net balances, cycles cancel, pay min(creditor, debtor)).
package com.mariocjun.algoviz

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/** Session-only "don't show the intro again" flag — resets when the process
 *  dies, so it is valid only for the current execution (per the owner's ask). */
object ExtremeTutorial { var dismissed = false }

@Composable
internal fun TutorialSheet(onDone: (dontShowAgain: Boolean) -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    // Scrim consumes taps so the animation behind doesn't react; closing is
    // explicit (X or "Começar"), never an accidental outside tap.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 460.dp).fillMaxHeight(0.92f).padding(14.dp)
                .clip(RoundedCornerShape(22.dp)).background(GLASS)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Min Cash Flow", color = EX_TXT, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                        .clickable { onDone(dontShow) }.semantics { contentDescription = "Fechar tutorial" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Fechar", tint = EX_TXT, modifier = Modifier.size(20.dp)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                MoneyPlayground()
                MotivationStrip()
                StoryBlock()
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = dontShow, onCheckedChange = { dontShow = it },
                    modifier = Modifier.semantics { contentDescription = "não mostrar de novo" })
                Text("não mostrar de novo", color = EX_DIM, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(EX_BLUE)
                        .clickable { onDone(dontShow) }
                        .semantics { contentDescription = "Começar" }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) { Text("Começar ▶", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ---- Interactive money playground ---------------------------------------------

private class Coin(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float = 0f)

@Composable
private fun MoneyPlayground() {
    val coins = remember { mutableStateListOf<Coin>() }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val wiggle = remember { Animatable(0f) }
    var tick by remember { mutableIntStateOf(0) }       // forces per-frame redraw

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                for (c in coins) { c.x += c.vx * dt; c.y += c.vy * dt; c.vy += 560f * dt; c.age += dt }
                if (coins.isNotEmpty()) coins.removeAll { it.age > 2.0f }
                tick++
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp))
            .background(EX_BG).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures {
                    scope.launch { wiggle.snapTo(1f); wiggle.animateTo(0f, tween(520)) }
                    val cx = size.width / 2f; val cy = size.height * 0.52f
                    val ang = Random.nextFloat() * 6.2832f
                    val sp = 240f + Random.nextFloat() * 230f
                    coins.add(Coin(cx, cy - 22f, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat() - 170f))
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            tick.let { }                            // observe tick so positions redraw each frame
            val cx = size.width / 2f; val cy = size.height * 0.52f
            val deg = (sin(wiggle.value * PI.toFloat() * 4f) * 12f * wiggle.value)
            rotate(deg, pivot = Offset(cx, cy + 36f)) { drawDude(cx, cy) }
            for (c in coins) {
                val a = (1f - c.age / 2.0f).coerceIn(0f, 1f)
                drawCircle(EX_GOLD.copy(alpha = a), radius = 11f, center = Offset(c.x, c.y))
                val st = TextStyle(color = EX_BG.copy(alpha = a), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val m = measurer.measure("$", st)
                drawText(textLayoutResult = m, topLeft = Offset(c.x - m.size.width / 2f, c.y - m.size.height / 2f))
            }
        }
        Text("toque no boneco — ele joga uns trocados 👆", color = EX_DIM, fontSize = 12.sp,
            modifier = Modifier.padding(8.dp))
    }
}

private fun DrawScope.drawDude(cx: Float, cy: Float) {
    val c = EX_BLUE
    drawCircle(c, radius = 16f, center = Offset(cx, cy - 24f))                 // head
    drawCircle(Color.White, 2.6f, Offset(cx - 5f, cy - 26f))                   // eyes
    drawCircle(Color.White, 2.6f, Offset(cx + 5f, cy - 26f))
    drawLine(c, Offset(cx, cy - 7f), Offset(cx, cy + 20f), strokeWidth = 8f, cap = StrokeCap.Round)   // body
    drawLine(c, Offset(cx, cy), Offset(cx - 15f, cy + 9f), strokeWidth = 5f, cap = StrokeCap.Round)   // arms
    drawLine(c, Offset(cx, cy), Offset(cx + 15f, cy + 9f), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(c, Offset(cx, cy + 20f), Offset(cx - 12f, cy + 38f), strokeWidth = 5f, cap = StrokeCap.Round) // legs
    drawLine(c, Offset(cx, cy + 20f), Offset(cx + 12f, cy + 38f), strokeWidth = 5f, cap = StrokeCap.Round)
}

// ---- Motivation + story -------------------------------------------------------

@Composable
private fun MotivationStrip() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniDude(EX_OWES, "A")
            Text("R\$ ×19", color = EX_GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = EX_DIM,
                modifier = Modifier.size(18.dp))
            MiniDude(EX_OWED, "B")
            Text("× 20 pessoas", color = EX_DIM, fontSize = 12.sp)
        }
        Text(
            buildAnnotatedString {
                append("Cada um pode dever aos outros ")
                key("19"); append(". Com "); key("20")
                append(" amigos isso dá 20 × 19 ÷ 2 = "); key("190")
                append(" dívidas possíveis — o máximo. É o ")
                withStyle(SpanStyle(color = EX_TXT, fontWeight = FontWeight.Bold)) { append("extremo") }
                append(".")
            },
            color = EX_TXT.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MiniDude(color: Color, label: String) {
    Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(color), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun StoryBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Para(buildAnnotatedString {
            append("Imagine um rolê onde, por algum motivo, ")
            withStyle(SpanStyle(color = EX_TXT, fontWeight = FontWeight.Bold)) { append("todo mundo deve a todo mundo") }
            append(". Dá pra simplificar essa bagunça?")
        })
        Head("1 · Só o saldo importa")
        Para(buildAnnotatedString {
            append("Se você deve R\$10 ao João e ele deve R\$10 a você, ninguém precisa pagar nada — ")
            col("cancela", EX_GOLD); append(". No geral: some tudo que cada um ")
            col("recebe", EX_OWED); append(" e tira tudo que ")
            col("deve", EX_OWES); append(". Sobra um só número: o saldo.")
        })
        Head("2 · Ciclos se cancelam")
        Para(buildAnnotatedString {
            append("A→B→C→A, cada um R\$10? O dinheiro dá a volta e ninguém fica devendo: o ciclo ")
            col("some", EX_GOLD); append(". É por isso que 190 dívidas encolhem tanto.")
        })
        Head("3 · Guloso: maior credor ↔ maior devedor")
        Para(buildAnnotatedString {
            append("Pega quem mais tem a "); col("receber", EX_OWED)
            append(" e quem mais "); col("deve", EX_OWES); append(". Paga-se o ")
            col("min(credor, devedor)", EX_BLUE)
            append(". Cada pagamento zera pelo menos uma pessoa. Repete até todo mundo zerar.")
        })
        Para(buildAnnotatedString {
            withStyle(SpanStyle(color = EX_OWED, fontWeight = FontWeight.Bold)) { append("190 dívidas → 1 pagamento") }
            append("  (Mário deve R\$67 a Cássia). É uma heurística quase-ótima — achar o mínimo exato de pagamentos é NP-difícil.")
        })
    }
}

@Composable
private fun Head(text: String) {
    Text(text, color = EX_BLUE, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Para(text: androidx.compose.ui.text.AnnotatedString) {
    Text(text, color = EX_TXT.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.col(s: String, color: Color) =
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(s) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.key(s: String) =
    withStyle(SpanStyle(color = EX_BLUE, fontWeight = FontWeight.Bold)) { append(s) }
