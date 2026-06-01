# UX_STUDY — estudo completo de UX/IHC (fase de planejamento)

Estudo de planejamento do algoviz, produzido por agentes críticos (read-only) +
pesquisa científica. **Nenhum código foi alterado** — é o blueprint da próxima
fase. Companion de `UX_TRANSITION_MAP.md`, `VALIDATION_LOG.md` e da skill
`validate-before-ship`.

Índice: §1 Personas · §2 Jornada (curva emocional) · §3 Spec das transições ·
§4 Plano de engajamento/retenção · **§5 Semântica de representação temporal (o
vacilo do playhead)** · §6 Roadmap faseado.

---

## 1. Personas (provisórias — Confiança: exploratória, validar com 5 usuários reais)

| | **Léo** (PRIMÁRIA) | **Marina** (SECUNDÁRIA) | **Prof. Almeida** (consideração) |
|---|---|---|---|
| Arquétipo | Vestibulando de SO/Algoritmos | Curiosa "tech satisfying" / ASMR | Docente em sala |
| Gatilho | **Externo** (prova) → interno | **Interno** (tédio/estética) | Externo (preparar aula) |
| Objetivo | *Entender de verdade* + auto-testar | Prazer sensorial + "qual sort ganha?" | Demonstrar fiel ao Maziero |
| Volta porque | testing effect + progresso + ASMR | recompensa variável (cada shuffle) + juiciness | fidelidade + confiabilidade na projeção |
| Hoje **perde** em | fim do Desafio (sem resumo/next) | cortes secos quebram o transe | finais em beco sem saída na frente da turma |

**Tese central (provada pela §5 do transition map):** "educativo" e "viciante"
**convergem** quando ancorados no aprendizado — atender Léo e Marina não exige
telas conflitantes. Maestria por desempenho, nunca por tempo de tela.

---

## 2. Jornada de Léo — curva emocional (2 vales perigosos)

```
😄5 │                                  ╱╲ (pico: 190→1 / finish flash)
🙂4 │   ╱╲(Maziero)      ╱‾‾‾‾‾╲     ╱   ╲
😐3 │ ╱   ╲(TR-H1)     ╱(tédio↘)╲  ╱     ╲___ ← FIM FRACO (peak-end pune aqui)
😕2 │       ╲________╱  TR-S1/D5            (TR-D6/M1: beco sem saída)
    │     ↑ TR-D2/D1 (vão + transport morto = vale da ativação)
    └─ DESCOBERTA ── 1ª SESSÃO ──── RECORRENTE ──── MAESTRIA/RETENÇÃO
```

- **Vale 1 — Ativação** (1ª pergunta do Desafio): TR-D2 (fecho a intro e nada acontece) + TR-D1 (transport morto). Quase abandona.
- **Vale 2 — O fim** (peak-end): picos de classe mundial seguidos de becos sem saída (TR-D6, TR-M1). É a memória que ele leva embora.

**3 maiores oportunidades** (score = (Freq+Sev+Alcance)×Solucionabilidade):
1. 🥇 **Finais → open loops** (~75) — peak-end+Zeigarnik; barato, atinge as 3 personas.
2. 🥈 **Vale de ativação do Desafio** (~60) — feedforward; quick win.
3. 🥉 **Modos visíveis + transe ASMR** (~48) — juiciness nos cortes secos.

---

## 3. Especificação das transições (resumo — detalhe em `UX_TRANSITION_MAP.md` §4)

Tokens de movimento a centralizar (Material 3): standard 250–300ms · emphasized
400–500ms · emphasized-decelerate (entrada) · emphasized-accelerate (saída) ·
spring 0.7 · spring bouncy 0.45 (clímax).

| TR | Solução | Padrão | Esforço | Impacto |
|---|---|---|---|---|
| TR-D1 | guard no ◀ (**bug real**) + dim 0.32 nos botões + "Responda acima ↑" | affordance honesta | P–M | Alto |
| TR-D2 | "Começar ▶" dispara `playing=true` (1º prompt nasce sozinho) | feedforward→ação | P | Alto |
| TR-M1 | banner sai do centro + scaleIn + CTAs "↻ Rever / Voltar / por quê?" | fade + open-loop | M | **Alto** |
| TR-P1 | fade through no output + pulse + haptic + "✓ Concluído" | **fade through** | M | Alto |
| TR-P2 | "Enviar" abaixo do output, revelado com scaleIn + "↓" | fade + feedforward | P–M | Alto |
| TR-S1 | tint/borda no canvas + "✏️ arraste para moldar" | fade + sinal de modo | P | Alto |
| TR-S2 | HUD "n×" sob o dedo + scale-stamp + haptic | microinteração | M | Alto |
| TR-R1 | 20 nós "florescem" + scale-stamp/glow no chip gateway | fade/expand + juiciness | M | Alto |
| TR-C1 | NavIndex sticky + chip ativo (scroll-spy) + highlight de chegada | sticky + sinal de posição | M | Alto |
| TR-D3 | `AnimatedVisibility` no prompt + `animateColorAsState` nas pills + ✓ stamp | fade + juiciness | M | Alto |

**Achado que vai além de UX:** **TR-D1 esconde um bug** — `onBack` (SchedActivity)
não tem o guard `challengePendingId == null` que `onFwd`/`onRun` têm, então o ◀
durante a pergunta dessincroniza o "Tick N". Corrigir junto com a camada visual.

**Sequência:** TR-D1 → TR-D2 → TR-M1 → TR-P1+P2 (juntos) → TR-C1 → TR-S1/S2 → TR-R1 → TR-D3 → 🟡.

---

## 4. Plano de engajamento/retenção (ético — Manipulation Matrix)

**Destrava tudo:** o app tem **ZERO persistência** (sem SharedPreferences/DataStore;
`ChallengeIntro`/`ExtremeTutorial` são `object` em memória que morrem com o processo).
**Pré-requisito de §4.1 e §4.7: criar `object Progress` local-only (sem PII, sem backend)** no `AlgovizApp.onCreate()`.

1. **Progresso/Coleção (Zeigarnik):** `sortsMastered (de 8)`, `schedulersMastered (de 7)` — só sobem **por desempenho** (acerto), nunca por tempo de tela. Home vira hub: anel "domina 4 de 15" + pips por tile + CTA "Continue: Heap — o único que falta na família O(n log n)".
2. **Dificuldade adaptativa (flow channel):** janela móvel das últimas 5 respostas; sobe na trilha (FCFS→RR→SJF…) com ≥80% acerto, segura com <40% — alvo: manter na faixa 40–80% (nem tédio, nem ansiedade). Sempre por cima do controle manual.
3. **Finais como ganchos (peak-end+Zeigarnik):**
   - Min Cash Flow: "41 dívidas → 1 pagamento · economia de 40" + open loop "e com 50 pessoas?" + CTAs **↻ Rever / Tentar com 50 → / Ver no Racha →**.
   - Scheduler: "Acertou 5 de 6 do Round Robin" + maestria + erro mais comum + CTAs **↻ / Próximo: SJF → / Como o RR decide?** (zera o placar por-round — corrige o bug de acúmulo X/Y).
4. **Interleaving (desirable difficulty):** modo **"🔀 Misto"** sorteia o algoritmo a cada pergunta (entre os já desbloqueados) — força recuperar *qual regra* antes de aplicar. Piora a performance imediata, melhora retenção/transferência (Bjork) → fricção honesta, não dark pattern.
5. **Mapa de juiciness (P0):** scale-stamp+pulse no ✓ do acerto; pip de maestria acendendo. Sempre atrelado a evento de **aprendizado**, nunca a ação trivial.
6. **Ética:** maestria só por desempenho; streak informativo (não punitivo/FOMO); controle manual sempre disponível; recompensa "da Caça" (conteúdo) não "da Tribo" (social); tudo local-only com botão de zerar; respeita o teto de 39 min do AutoCloseGuard.
7. **Métricas (local-only):** taxa de acerto/algoritmo, algoritmos dominados (KPI-norte), time-on-task **educativo** (reusar o cronômetro de foreground do AutoClose), retenção D1/D7 (`sessionDays`), % do tempo na faixa de flow 40–80%. Painel de debug escondido (gesto, como o 10-tap) para validar no N975F sem servidor.

---

## 5. ⚠️ Semântica de representação temporal — o vacilo do playhead (NOVO EIXO)

> Levantado pelo dono (2026-06-01), **escapou de 3 rodadas de review** (motion/
> feedforward não é semântica de representação). Reconhecido como vacilo: o
> próximo review ganha este eixo obrigatório, **no app todo**.

### 5.1 O problema (confirmado no código)
No Gantt do Scheduler (`SchedActivity.kt`):
- `fun x(t) = left + t * cw` (L606) → o tick `t` mapeia para a **borda esquerda** da sua célula.
- A célula "executando agora" = `runningAt(r, currentT)` é desenhada em `x(currentT)` (L658-659) — **à direita** do playhead.
- O playhead: `val px = x(animT)` (L676) → na **borda esquerda** da célula atual.

Resultado: a célula que está executando aparece **à direita** da linha → o olho lê
"vai iniciar/futuro", enquanto o `StatusStrip` diz "Executando t1". **Contradição
semântica.** Pior no Modo Desafio: a "Próxima" parece a própria célula atual.

### 5.2 A ciência (por que está errado)
- **Aigner, Miksch, Schumann, Tominski — *Visualization of Time-Oriented Data***: distinguem **instantes (pontos)** de **intervalos**. Um tick de escalonamento é um **intervalo** `[t, t+1)` (granularidade discreta), mas o playhead é um **instante** (linha). Misturar os dois **sem resolver a fronteira** é a raiz.
- **Convenção universal do "now-line / today-line"** (Monday, Tempo, FineReport, OpenProject, MS Project): a linha vertical = AGORA; **à esquerda = passado/concluído, à direita = futuro/planejado**. É um **mapeamento espaço-temporal** quase universal (Norman: *mapping*; metáfora cultural tempo→esquerda-direita). O algoviz **viola**: célula atual à direita = parece futuro.
- **Half-open interval / fencepost** (Dijkstra, EWD831 *"Why numbering should start at zero"*): a linha em `x(t)` cai na **fronteira esquerda** do intervalo `[t, t+1)` — ambígua entre "fim do tick t−1" e "início do tick t". A ambiguidade é estrutural, não estética.

### 5.3 Correções possíveis (a decidir com o dono)
- **Opção A (alinhar à convenção):** tudo à **esquerda** da linha = já executado. A célula "executando/recém-executada" fica **à esquerda** do playhead (o playhead marca o **fim** do tick processado, `x(currentT)` = "já rodei até aqui"). Mais fiel ao "now-line".
- **Opção B (instante dentro do intervalo):** playhead no **centro** da célula atual (`x(currentT + 0.5)`) — a linha **atravessa** a célula que executa, desambiguando (célula sob a linha = agora; esquerda = passado; direita = futuro). Aigner: representar o "agora" discreto como **intervalo destacado** (a célula glow), não só uma fronteira.
- **Opção C:** rebaixar a linha a decorativa (centro) e deixar o **glow da célula** ser o "agora" (signifier primário), com legenda explícita.

Recomendação: **B ou A** + atualizar a `GanttLegend` para nomear a convenção ("│ = agora · à esquerda = já executou").

### 5.4 Este eixo é do APP TODO — outros candidatos a auditar
A mesma classe de ambiguidade (instante vs intervalo, fronteira, mapeamento) deve
ser validada em toda representação temporal/posicional:
- **TM-1** 🔴 Scheduler — playhead vs célula (acima).
- **TM-2** 🟡 Min Cash Flow — o **scrubber/cursor**: o thumb marca o "passo atual" (instante) sobre steps discretos (Build/Absorb/Settle). Verificar se "antes/depois do thumb" comunica passado/futuro corretamente, e se o salto de scrub respeita a fronteira.
- **TM-3** 🟡 Sort — a **fronteira ordenado↔não-ordenado** (região verde): a barra de fronteira pertence à região ordenada ou não? E os índices ativos `hi_a/hi_b` (instante) sobre as barras (posições) — o destaque comunica "lendo agora" sem sugerir "vai escrever"?
- **TM-4** 🟢 Sort/Gantt — eixos e rótulos de tempo (marcas a cada 5): o rótulo "5" fica na borda da célula 5 ou no centro? Consistência com o playhead.

### 5.5 Lição de processo
Os reviews (incl. o ex-Apple) cobriram **carga cognitiva, onboarding, affordance,
feedforward, motion** — mas **não a corretude semântica da representação** (o que
cada pixel *significa* no modelo temporal). **Novo Gate** no `validate-before-ship`:
para toda visualização temporal/posicional, validar **instante vs intervalo,
fronteiras (fencepost) e o mapeamento espaço-temporal** contra a convenção. O
"ex-Apple" volta como "ex-AlgoViz" — recontratado com esta régua. 😄

---

## 6. Roadmap faseado (proposto — sem datas; ordem por alavanca)

| Fase | Foco | Itens |
|---|---|---|
| **v0.6.6** | Corrigir o que **confunde/contradiz** | **TM-1** (playhead — semântica) · TR-D1 (bug do ◀ + dim) · TR-D2 (vão de ativação) |
| **v0.6.7** | **Finais** (peak-end, maior retorno) | TR-M1 + TR-D6 (telas de fim com CTA/open-loop) · `object Progress` (persistência local) |
| **v0.7.0** | **Coleção & juiciness** | Home como hub de progresso (§4.1) · juiciness P0 (§4.5) · TR-D3 · TR-P1/P2 |
| **v0.7.x** | **Motion como idioma** | tokens centralizados · fade-through/shared-axis nos cortes secos (TR-S/R/C) · TM-2/TM-3 |
| **v0.8.0** | **Aprendizado profundo** | dificuldade adaptativa (§4.2) · modo Misto/interleaving (§4.4) · escalas Sort/MinCashFlow |

Tudo respeita: dark/glass nativo, Material 3 + acabamento Apple, local-only/sem PII,
teto de 39 min. **Próximo passo:** validar este estudo (e a semântica TM-*) com o
revisor de IHC antes de qualquer implementação.

## Fontes
- Aigner, Miksch, Schumann, Tominski — [*Visualization of Time-Oriented Data*](https://books.google.com/books/about/Visualization_of_Time_Oriented_Data.html?id=YnDivwba2nkC) · [cap. técnico (uni-rostock, PDF)](https://vca.informatik.uni-rostock.de/~ct/publications/Aigner15VisTechniquesForTime.pdf)
- Convenção now-line: [Monday Gantt](https://support.monday.com/hc/en-us/articles/360015643840), [Tempo](https://help.tempo.io/gantt/latest/gantt-chart-elements), [OpenProject](https://www.openproject.org/docs/user-guide/gantt-chart/)
- Dijkstra, EWD831 — *Why numbering should start at zero* (half-open intervals / fencepost)
- Norman — mapping; e os princípios de IHC educacional em `UX_TRANSITION_MAP.md` §5.
