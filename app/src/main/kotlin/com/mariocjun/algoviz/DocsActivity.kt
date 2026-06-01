// DocsActivity — "Códex": the project's documentation as a textual mini-app.
// Editorial, disruptive typography in the spirit of Sci-Hub (deep teal, cream
// paper, a gold key + raven emblem, serif display + monospace tags): the
// consolidation of what algoviz is, plus the quality posture mapped to ABNT
// NBR ISO/IEC 25010, plus the engineering backlog. All content is data (the
// CODEX list) rendered by one styled walker — add a section = add a node.
package com.mariocjun.algoviz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Sci-Hub-ish palette: deep teal, cream paper, gold key -------------------
private val DOC_BG = Color(0xFF07201E)
private val DOC_PANEL = Color(0xFF0C2B28)
private val DOC_INK = Color(0xFFEDE7D6)
private val DOC_DIM = Color(0xFF87A39D)
private val DOC_ACCENT = Color(0xFF34D1BF)
private val DOC_KEY = Color(0xFFE8B62E)
private val GREEN = Color(0xFF3DCF7A)
private val AMBER = Color(0xFFE8B62E)
private val CORAL = Color(0xFFFF6B5E)

private enum class St(val label: String, val color: Color) {
    OK("ATENDE", GREEN), PARCIAL("PARCIAL", AMBER), LACUNA("LACUNA", CORAL)
}

private sealed interface Node
private class Sec(val n: String, val title: String) : Node
private class Sub(val text: String) : Node
private class P(val text: String, val drop: Boolean = false) : Node
private class B(val text: String) : Node
private class Q(val name: String, val st: St, val note: String) : Node
private class Pre(val text: String) : Node
private object Rule : Node

class DocsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = DOC_BG, surface = DOC_PANEL, onBackground = DOC_INK, onSurface = DOC_INK)) {
                Surface(color = DOC_BG) {
                    AutoCloseGuard { Box(Modifier.safeDrawingPadding()) { CodexScreen() } }
                }
            }
        }
    }
}

@Composable
private fun CodexScreen() {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val secIndices = remember {
        CODEX.withIndex().filter { (_, n) -> n is Sec }
            .associate { (i, n) -> (n as Sec).n to i + 2 }  // +2 for masthead + navindex items
    }
    Box(Modifier.fillMaxSize().background(DOC_BG), contentAlignment = Alignment.TopCenter) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 22.dp),
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Masthead()
                Spacer(Modifier.height(8.dp))
            }
            item {
                NavIndex(secIndices.keys.sorted()) { num ->
                    secIndices[num]?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } }
                }
                Spacer(Modifier.height(12.dp))
            }
            items(CODEX) { node -> DocNode(node) }
            item {
                Spacer(Modifier.height(40.dp))
                Text("∎  algoviz · código aberto · feito com rigor", color = DOC_DIM,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NavIndex(sections: List<String>, onJump: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { num ->
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(DOC_PANEL)
                    .clickable { onJump(num) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("§$num", color = DOC_KEY, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Masthead() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(64.dp)) { drawRavenKey() }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("CÓDEX", color = DOC_INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black,
                fontSize = 38.sp, letterSpacing = 2.sp)
            Text("remover as barreiras ao conhecimento", color = DOC_ACCENT,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.5.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("documentação viva · engenharia & qualidade ABNT NBR ISO/IEC 25010",
        color = DOC_DIM, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun DocNode(node: Node) {
    when (node) {
        is Sec -> {
            Spacer(Modifier.height(26.dp))
            Text("§ ${node.n}", color = DOC_KEY, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            Text(node.title, color = DOC_INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 26.sp, lineHeight = 30.sp)
            Spacer(Modifier.height(10.dp))
        }
        is Sub -> {
            Spacer(Modifier.height(12.dp))
            Text(node.text, color = DOC_ACCENT, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            Spacer(Modifier.height(4.dp))
        }
        is P -> {
            if (node.drop && node.text.isNotEmpty()) {
                Row {
                    Text(node.text.first().toString(), color = DOC_KEY, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Black, fontSize = 52.sp, lineHeight = 46.sp,
                        modifier = Modifier.padding(end = 8.dp))
                    Text(node.text.drop(1), color = DOC_INK, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 25.sp)
                }
            } else {
                Text(node.text, color = DOC_INK, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 25.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        is B -> Row(Modifier.padding(bottom = 6.dp)) {
            Text("▸ ", color = DOC_ACCENT, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Text(node.text, color = DOC_INK.copy(alpha = 0.92f), fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 22.sp)
        }
        is Q -> Column(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                .background(DOC_PANEL).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, color = DOC_INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(50)).background(node.st.color.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(node.st.label, color = node.st.color, fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(node.note, color = DOC_DIM, fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 20.sp)
        }
        is Pre -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.32f)).padding(12.dp)) {
            Text(node.text, color = DOC_ACCENT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        }
        is Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(DOC_DIM.copy(alpha = 0.25f)))
    }
}

// ---- Raven + key emblem (a nod to Sci-Hub) ------------------------------------
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRavenKey() {
    val w = size.width; val h = size.height
    // perched raven silhouette (cream on teal)
    val body = Path().apply {
        moveTo(w * 0.16f, h * 0.74f)                       // tail
        cubicTo(w * 0.10f, h * 0.50f, w * 0.30f, h * 0.30f, w * 0.55f, h * 0.30f)  // back
        cubicTo(w * 0.66f, h * 0.30f, w * 0.70f, h * 0.20f, w * 0.74f, h * 0.16f)  // neck->head up
        cubicTo(w * 0.82f, h * 0.20f, w * 0.80f, h * 0.34f, w * 0.72f, h * 0.40f)  // head front
        cubicTo(w * 0.78f, h * 0.52f, w * 0.66f, h * 0.72f, w * 0.46f, h * 0.78f)  // breast
        cubicTo(w * 0.34f, h * 0.81f, w * 0.24f, h * 0.80f, w * 0.16f, h * 0.74f)  // belly->tail
        close()
    }
    drawPath(body, DOC_INK)
    // beak (gold-ish wedge pointing right)
    drawPath(Path().apply {
        moveTo(w * 0.78f, h * 0.26f); lineTo(w * 0.96f, h * 0.30f); lineTo(w * 0.78f, h * 0.34f); close()
    }, DOC_INK)
    // eye
    drawCircle(DOC_BG, radius = w * 0.022f, center = Offset(w * 0.73f, h * 0.27f))
    // the key of knowledge, held at the beak (gold): bow (ring) + stem + teeth
    val ky = h * 0.30f
    drawCircle(DOC_KEY, radius = w * 0.055f, center = Offset(w * 0.88f, ky), style = Stroke(w * 0.028f))
    drawLine(DOC_KEY, Offset(w * 0.825f, ky), Offset(w * 0.70f, ky), strokeWidth = w * 0.03f, cap = StrokeCap.Round)   // stem toward beak
    drawLine(DOC_KEY, Offset(w * 0.74f, ky), Offset(w * 0.74f, ky + h * 0.06f), strokeWidth = w * 0.025f, cap = StrokeCap.Round)  // tooth
    drawLine(DOC_KEY, Offset(w * 0.78f, ky), Offset(w * 0.78f, ky + h * 0.06f), strokeWidth = w * 0.025f, cap = StrokeCap.Round)  // tooth
}

// ---- The document -------------------------------------------------------------
private val CODEX: List<Node> = listOf(
    P("algoviz nasceu como um template C++/NDK para Android e virou um acervo de mini-apps " +
      "didáticos: cada um pega um algoritmo clássico e o torna visível, audível e — de preferência — " +
      "addictivo. O motor é C++; os pixels são Compose; a régua é nível Apple.", drop = true),

    Sub("Como aprender com este app"),
    B("Sort Visualizer — escolha um algoritmo, toque ▶. Use ◀▶ para avançar passo a passo e 'Explicação IA' para entender a lógica."),
    B("Scheduler — toque ▶ para animar o Gantt. Toque ℹ para ver a heurística de cada algoritmo de escalonamento."),
    B("Racha — vá à aba Grafo. Toque '✨ caso extremo' para ver 190 dívidas colapsarem em 1 pagamento."),
    B("Min Cash Flow — deixe o tutorial abrir e avance os 4 atos com ▶. Pause e observe a fórmula no display."),
    B("Profiler — toque Run para medir o hardware do seu dispositivo (NEON, SIMD, STREAM, latência)."),
    B("Códex — você está aqui: documentação viva e mapa de qualidade do projeto."),
    Rule,

    Sec("1", "Os mini-apps"),
    Sub("Sort visualizer"),
    P("8 ordenações escritas uma só vez como corrotinas C++ (Generator<Step>): o benchmark drena o " +
      "fluxo e conta operações; o Compose consome o MESMO fluxo e anima. Modo corrida, áudio ASMR " +
      "pentatônico, barras em arco-íris por valor."),
    Sub("Scheduler trainer"),
    P("Escalonador de CPU no estilo do livro do Maziero: Gantt swimlane (preenchido = executando, " +
      "vazado = esperando), 7 algoritmos, e um cartão didático com a heurística de cada um."),
    Sub("Racha"),
    P("Divide a conta entre amigos e fecha com o menor número de pagamentos (simplificação gulosa de " +
      "dívidas — o que o Splitwise faz). Três abas: Saldos, Acerto e Grafo."),
    Sub("Min Cash Flow"),
    P("O algoritmo guloso do Racha como aula visual: o grafo COMPLETO de 20 pessoas — C(20,2)=190 " +
      "dívidas — colapsa em 1 pagamento. Quatro atos (encher → cheio → podar → acerto), tutorial " +
      "interativo estilo 3Blue1Brown, raios, áudio e pops (deve/cancela/recebe)."),
    Sub("Profiler"),
    P("Benchmarks de CPU (NEON/SIMD com gating por HWCAP), sensores, Camera2 e fingerprint de SoC; " +
      "exporta JSON. ELF standalone (cppbench) roda sem APK."),
    Sub("Códex"),
    P("Este app: a documentação viva do projeto, incluindo o mapa de qualidade abaixo."),

    Sec("2", "Arquitetura"),
    P("O C++ é dono do modelo; o Compose é dono dos pixels. Os núcleos (sorts, escalonador, " +
      "redução de dívidas, engines de viz) são livres de NDK e testados no host — então a CI testa o " +
      "algoritmo de verdade, não uma reimplementação. A ponte é JNI; o áudio é AAudio."),
    Pre("Kotlin/Compose  ──JNI──▶  libalgoviz.so (C++23)\n" +
        "  MainActivity            bench/  (registry + fold dispatch)\n" +
        "  Viz/Sched/...           algoviz/ (corrotinas Generator<Step>)\n" +
        "  per-frame buffer ◀──    viz/ (single/race) · audio_engine (AAudio)"),
    B("Padrão concept + tupla + fold: um benchmark/sort malformado é erro de compilação."),
    B("SIMD com gating por getauxval(AT_HWCAP) — nunca por /proc/cpuinfo (que mente)."),
    B("-O3 + LTO + dead-code stripping; -march por arquivo (dotprod/i8mm/sve2)."),

    Sec("3", "Linha do tempo"),
    B("v0.4.0 — base do template + CI para SDK 35."),
    B("v0.5.0 — Scheduler, Racha + grafo, cartão de heurística, auto-close (saiu com bug de overlay)."),
    B("v0.5.1 — corrige o overlay que engolia toques; Extremo (41 dívidas)."),
    B("v0.5.2 — Min Cash Flow vira app próprio; grafo completo de 190; 4 atos."),
    B("v0.5.3 — tutorial interativo, pops flutuantes, passe de acessibilidade + tablet."),

    Sec("4", "Qualidade — ABNT NBR ISO/IEC 25010"),
    P("A norma brasileira de qualidade de produto de software (modelo SQuaRE, ed. 2011) define 8 " +
      "características. Abaixo, a postura honesta do algoviz em cada uma — com lacunas assumidas."),
    Q("Adequação funcional", St.OK,
      "Núcleos provados no host: golden 7/7 do escalonador, 11 testes do Racha, 5 da redução, " +
      "replay de passos dos sorts (1400+ asserções). O que a UI anima é o mesmo código testado."),
    Q("Eficiência de desempenho", St.OK,
      "C++23 -O3/LTO, NEON/SIMD com pinagem por cluster, snapshot por frame zero-cópia (sem GC). " +
      "O próprio Profiler mede GFLOPS/GB-s com mediana e CV."),
    Q("Compatibilidade", St.PARCIAL,
      "Coexiste bem (sem serviços de fundo); interoperabilidade via JSON/paste.rs. Sem integrações " +
      "externas nem conteúdo compartilhado entre usuários (Racha é local)."),
    Q("Usabilidade", St.OK,
      "Régua Apple: M3 escuro, gestos, animações, tutorial 3B1B. Acessibilidade: alvos 44–50dp, " +
      "content-desc nos controles, narração via texto de status; layout de tablet (capado/proporcional). " +
      "Ressalva: o Canvas em si é mudo para leitor de tela (mitigado pelo status)."),
    Q("Confiabilidade", St.PARCIAL,
      "Handler de crash nativo (SA_SIGINFO → dump), testes de host, smoke na CI. Estado é efêmero " +
      "(nada persistido) — recuperabilidade limitada por escolha."),
    Q("Segurança", St.PARCIAL,
      "Sem PII no repositório (identificadores do aparelho ficam em arquivo gitignored); só a permissão " +
      "INTERNET, e o upload é opt-in. Assinado com debug keystore (não elegível à Play). Sem auth no upload."),
    Q("Manutenibilidade", St.PARCIAL,
      "Modular e testável (núcleos NDK-free), mapa em CLAUDE.md/AGENTS.md, clang-tidy + lizard na CI. " +
      "Dívidas: desenho de aresta duplicado, UI sem testes instrumentados, strings inline."),
    Q("Portabilidade", St.PARCIAL,
      "Compose adapta retrato/paisagem/tablet; instalável via APK. ELF só arm64-v8a; sem x86_64; " +
      "debug key exige desinstalar antes de atualizar."),
    P("Nota: a ISO revisou a 25010 em 2023 (acrescentou Segurança física/Safety e renomeou " +
      "Usabilidade para Capacidade de interação). O mapa acima segue a edição 2011, que é a adotada " +
      "pela ABNT NBR."),

    Sec("5", "O que ainda vamos melhorar"),
    P("Transparência é parte da qualidade: estas são as lacunas que já identificamos e escolhemos não fechar ainda — " +
      "seja por prioridade, seja para fazer direito. Ver lacunas assumidas é também uma lição de engenharia."),
    B("Testabilidade: a UI Compose não tem testes instrumentados (só smoke). Adicionar androidTest/Robolectric."),
    B("Modularidade: extrair um GraphDraw compartilhado (Racha e Min Cash Flow duplicam o desenho de aresta curva)."),
    B("Portabilidade/i18n: externalizar as strings pt-BR para res/values (+ values-en)."),
    B("Consistência: padronizar dp.toPx() no Canvas (hoje há px cru misturado com sp)."),
    B("Documentação: adicionar CHANGELOG.md (hoje as notas de release são automáticas)."),
    B("Segurança: revisar o upload (confirmação explícita + anonimização)."),
    B("CI: considerar tornar clang-tidy/lizard bloqueantes (hoje são informativos)."),
    Rule,
    P("Excelência aqui não é perfeição — é deixar cada lacuna visível e decidida. Este Códex é a " +
      "régua: quando uma linha acima virar ATENDE, atualize-a."),
)
