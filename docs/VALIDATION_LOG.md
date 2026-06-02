# VALIDATION_LOG — apontamentos de validação do algoviz

Registro **vivo** das gates de validação (ver a skill `validate-before-ship`).
Cada validação de UI/release **acrescenta** apontamentos aqui; os resolvidos são
marcados `✅`, **nunca apagados** — este log é a memória crescente da dívida de
UX/qualidade do projeto.

**Severidade:** 🔴 crítico (bloqueia o ship) · 🟡 médio (dívida rastreada) ·
🟢 polish. **Regra de fechamento:** não lançar uma área com 🔴 aberto.

---

## Apontamentos ABERTOS

### Modo Desafio — Scheduler (origem: crítica HCI 2026-06-01; re-review ex-Apple)

| # | Sev | Apontamento | Princípio HCI | Status |
|---|-----|-------------|---------------|--------|
| MD-1 | 🔴 | Ativar o Desafio não mostra instrução; estado visual idêntico exceto a cor do chip | Gulf of execution; Nielsen #1/#10 | ✅ v0.6.4 — `ChallengeIntroSheet` (3 passos) abre na 1ª ativação |
| MD-2 | 🔴 | Sem signifier do gesto — risco de tentar arrastar a pill p/ a CPU | Norman (signifier); Nielsen #5 | ✅ v0.6.4 — "👆 Toque na tarefa destacada…" no prompt |
| MD-3 | 🔴 | Sem onboarding, ao contrário do Min Cash Flow / Sort | Nielsen #4 (consistência) | ✅ v0.6.4 — sheet no padrão da casa |
| MD-4 | 🟡 | Pills clicáveis não se distinguem das READY normais | Nielsen #6 | ✅ v0.6.4 — dim 0.4 nas não-candidatas |
| MD-5 | 🟡 | Feedback cor-only (verde×vermelho) — daltonismo | WCAG 1.4.1 | ✅ v0.6.4 — ícones ✓/✗ pareados |
| MD-6 | 🟡 | Salto de olhar: prompt/pills no rodapé, Gantt no meio | Gestalt (proximidade) | aberto |
| MD-7 | 🟡 | Placar "🎯 1/2" ambíguo e dentro do chip toggle (tocar desliga o modo) | Nielsen #1/#2; mapeamento | ✅ v0.6.4 — placar "🎯 N de M" movido p/ StatusStrip (não-clicável) |
| MD-8 | 🟢 | Botão segue "Executar" no modo desafio | Nielsen #1 | aberto |
| MD-9 | 🟢 | Prompt não recapitula a regra do algoritmo | Nielsen #6 | ✅ v0.6.4 — "Tick N · FCFS — ordem de chegada" |
| **MD-10** | 🔴 | **A resposta vazava: StatusStrip mostrava "Próxima: t2" = o alvo do desafio** | Integridade do teste (recuperação ativa) | ✅ v0.6.4 — "Próxima: 🎯 adivinhe!" enquanto pendente |
| **MD-11** | 🔴 | Tap target da pill ~36 dp (< 44/48 dp) | Lei de Fitts / HIG 44pt | ✅ v0.6.4 — `heightIn(min=48.dp)` |
| MD-12 | 🟡 | Passo 3 do sheet empilhava 3 ideias numa linha | Chunking | ✅ v0.6.4 — reescrito enxuto |
| MD-13 | 🟡 | Sheet não vendia o "porquê" (testing effect) | Motivação no onboarding | ✅ v0.6.4 — "Prever antes de ver fixa o algoritmo…" |
| MD-14 | 🟡 | Delay de acerto (500 ms) curto p/ ler o reveal | Tempo de leitura | ✅ v0.6.4 — 800 ms acerto / 1100 ms erro |
| MD-15 | 🟡 | Feedback troca de cor por corte seco (sem cross-fade) | Movimento/continuidade (HIG) | parcial — prompt vira veredito; cross-fade ainda 🟢 aberto (MD-22) |
| **MD-20** | 🔴 | **Resposta vazava no INTERVALO entre perguntas**: faixa mostrava "Próxima: t3" por ~0,6 s antes da pergunta seguinte (hideNext só cobria o pending) | Integridade do teste | ✅ v0.6.5 — `hideNext = challengeMode` (esconde sempre que o modo está ligado) |
| MD-21 | 🟡 | Instrução obsoleta no feedback: "👆 Toque na tarefa" persistia após já ter respondido; sem fechamento/feedforward | Gulf of evaluation; Nielsen #1 | ✅ v0.6.5 — prompt vira veredito "Acertou! ✓ / Era X ✗ · Próxima pergunta em instantes…" |
| MD-22 | 🟢 | Transração prompt↔veredito↔próxima ainda é corte seco (sem AnimatedVisibility/Crossfade) | Movimento (HIG) | aberto |
| **MD-23** | 🔴 | **"Modo Desafio" é um toggle FANTASMA**: ativá-lo não muda nada visível; só vira desafio ao Executar. Deve ser um MODO real (segmented control Assistir/Desafio), com identidade cromática vibrante própria e recompensa mais viciante | Mode error (Norman 1981); Tesler "NO MODES"; Nielsen #1 | aberto (v0.6.7) — spec em `UX_STUDY.md` §6 |
| MD-16 | 🟢 | Cor ACCENT na microcópia de gesto compete com a pergunta | Hierarquia tipográfica | aberto |
| MD-17 | 🟢 | Sheet sem animação de entrada nem "pular" | Movimento; controle | aberto |
| MD-18 | 🟢 | "Não mostrar de novo" não persiste entre sessões (em memória) | Respeito ao recorrente | aberto |
| MD-19 | 🟢 | a11y do sheet/pills: foco, ordem, contentDescription dos ✓/✗ | TalkBack / foco | aberto |

### Backlog técnico (origem: Códex §5 — qualidade ABNT NBR ISO/IEC 25010)

| # | Sev | Apontamento | Status |
|---|-----|-------------|--------|
| BT-1 | 🟡 | `GraphDraw` duplicado: Racha e Min Cash Flow repetem o desenho de aresta curva | aberto |
| BT-2 | 🟡 | Strings pt-BR hardcoded — externalizar para `res/values` (+ `values-en`) | aberto |
| BT-3 | 🟡 | UI Compose sem testes instrumentados (só smoke) — falta androidTest/Robolectric | aberto |
| BT-4 | 🟢 | `dp.toPx()` inconsistente no Canvas (px cru misturado com sp) | aberto |
| BT-5 | 🟢 | Falta `CHANGELOG.md` (notas de release são automáticas) | aberto |
| BT-6 | 🟡 | Upload paste.rs sem confirmação explícita nem anonimização | aberto |
| BT-7 | 🟢 | clang-tidy/lizard informativos na CI — considerar torná-los bloqueantes | aberto |

---

## Apontamentos RESOLVIDOS

| # | Sev | Apontamento | Resolvido em |
|---|-----|-------------|--------------|
| FN-1 | 🔴 | Bonequinho do tutorial Min Cash Flow congelava o app no S24 Ultra (loop a 120 Hz) | ✅ v0.5.5 |
| FN-2 | 🔴 | Cliques repetidos no bonequinho acumulavam coroutines (backlog do wiggle) | ✅ v0.5.5 |
| FN-3 | 🔴 | Modo Desafio: tarefa que chega no tick do desafio ficava não-clicável | ✅ v0.6.1 |
| FN-4 | 🔴 | Modo Desafio: troca de algoritmo durante delay deixava coroutine órfã avançando o tick | ✅ v0.6.1 |
| FN-5 | 🔴 | MainActivity (Profiler) não compilava — `this` resolvia p/ BoxScope | ✅ pós-v0.6.3 |
| FN-6 | 🔴 | CI release/smoke: SDK desatualizado + wrapper gitignored não gerado | ✅ pós-v0.6.3 |

---

## Estados de produto (STD-*) — varredura heurística (ver `docs/RELEASE_PLAN.md`)
Lacunas que o plano de transições/motion não cobria. **STD-1 🔴 Gemini/IA** (feature
em produção viola Nielsen #9: erro colapsado no sucesso, sem cancelar/offline) ·
**STD-2 🔴 empty states** · **STD-3 🔴 erro/retry** · **STD-6 🔴 i18n+moeda+headline
hardcoded (bug)** · **STD-9 🔴 upload sem confirmação (privacidade)** · STD-4/5/7/8/10/11/12 🟡
(loading, offline, a11y, responsividade, settings/persistência, design system, onboarding
global) · STD-13/14 🟢. **Correção de rumo:** `object Progress` (persistência) sobe p/ a 1ª
release — sem ela a retenção é falsa. TR-D6 reclassificado: esconde bug de placar acumulado.

## Semântica de representação temporal (ver `docs/UX_STUDY.md` §5)
Eixo NOVO, levantado pelo dono — escapou de 3 reviews (motion ≠ semântica).

| # | Sev | Apontamento | Princípio | Status |
|---|-----|-------------|-----------|--------|
| TM-1 | 🔴 | Scheduler: playhead na borda ESQUERDA da célula atual → a célula "executando" fica à direita da linha (parece futuro); contradiz "Executando t1" | now-line convention (esq=passado); instante×intervalo (Aigner); fencepost (Dijkstra) | aberto (v0.6.6) |
| TM-2 | 🟡 | Min Cash Flow: scrubber/cursor sobre steps discretos — auditar fronteira passado/futuro do thumb | idem | a auditar |
| TM-3 | 🟡 | Sort: fronteira ordenado↔não-ordenado (a barra-limite pertence a quem?) + hi_a/hi_b "lendo" sem sugerir "escrevendo" | idem | a auditar |
| TM-4 | 🟢 | Rótulos de tempo do eixo (borda vs centro da célula) — consistência com o playhead | idem | a auditar |

## Transições (ver `docs/UX_TRANSITION_MAP.md`)
Mapa completo das transições dos 6 mini-apps + critérios avançados (Material
Motion, microinterações, feedforward) + princípios de IHC educacional/viciante
(flow, desirable difficulties, Hook model, juiciness, Zeigarnik, peak-end).
**9 🔴 + ~13 🟡 + ~5 🟢 de transição** (TR-*), nenhum bloqueia uma release de
correção — é o backlog do **próximo eixo do app** (levar as transições ao nível
Apple-grade do conteúdo). Top 🔴: TR-S1 (draw mode invisível), TR-D1 (transport
morto na pergunta), TR-M1/TR-D6 (finais em beco sem saída), TR-P1/P2 (Profiler
sem moment-of-completion/feedforward), TR-R1 (chip muda significado), TR-C1 (nav
do Códex sem "você está aqui").

## Histórico de validações

- **2026-06-01 — sessão de teste de toque (N975F, build debug v0.6.3+)**
  Gate 0 ✅ build/install · Gate 1 ✅ taps (bonequinho 20×, transporte 40×,
  auto-close 39-min, Sort, Desafio) — zero crash/ANR no logcat · Gate 3 ⚠️
  crítica HCI do Modo Desafio = **2,5/10 no onboarding** → abriu MD-1…MD-9.
  Veredito: **DO-NOT-SHIP** enquanto MD-1/2/3 (🔴) abertos.
- **2026-06-01 — feedbacks do dono (planejamento): modo fantasma + estética do Extremo**
  **MD-23 🔴** (Modo Desafio é toggle invisível → deve ser MODO real: segmented
  control + identidade cromática vibrante + recompensa viciante; Norman/Tesler).
  **TR-M5** (revelação preguiçosa do Extremo: arestas devem **crescer da origem ao
  destino** (Trim Path/draw-on), em **ordem aleatória** conectando extremos opostos,
  com **poda dinâmica**; reduzir p/ ~105 dívidas se melhorar — "a estética vale").
  Specs em `UX_STUDY.md` §6 e `UX_TRANSITION_MAP.md` §3.4. Sem código (fase de planejamento).
- **2026-06-01 — estudo de UX completo (planejamento, sem código) + vacilo TM-1**
  3 agentes críticos produziram `docs/UX_STUDY.md` (personas+jornada, spec das
  transições, plano de engajamento ético) e `docs/UX_TRANSITION_MAP.md` (9🔴 TR-*
  + princípios educacionais). **O dono pegou um vacilo que os reviews perderam**:
  a semântica do playhead do Gantt (TM-1 🔴) — a linha do "agora" está na borda
  esquerda da célula atual, fazendo a tarefa em execução parecer futura. Adicionado
  o **Gate 3.5 (semântica de representação)** ao pipeline e o eixo TM-* (app todo).
- **2026-06-01 — v0.6.5 RELEASED** (APK + ELF, CI verde) — fix do answer-leak.
- **2026-06-01 — correção do Modo Desafio + re-review ex-Apple (v0.6.4)**
  Resolvidos MD-1..5, MD-7, MD-9 (1ª rodada). Re-review ex-Apple subiu o
  onboarding **2,5 → 6,5** e revelou 2 🔴 novos: **MD-10** (resposta vazava no
  StatusStrip) e **MD-11** (tap target < 44 dp). Ambos corrigidos + MD-12/13/14
  (passo 3, "porquê", timing). Validado no device: prompt sem spoiler, placar
  separado, pills 48 dp, ✓/✗ — zero crash. **Abertos restantes (não-bloqueantes):**
  MD-6, MD-8, MD-15..19 (🟡/🟢, débito rastreado p/ v0.6.5). Área Modo Desafio
  **sem 🔴 aberto → liberada para ship.**
- **2026-06-01 — v0.6.4 RELEASED (APK + ELF publicados, CI verde)**, depois
  re-review ex-Apple focado em **transições/feedforward** (sequência de frames,
  não estáticos). Revelou **MD-20 🔴**: a resposta vazava no intervalo entre
  perguntas ("Próxima: t3"). Como a v0.6.4 já saíra, corrigido em **v0.6.5**
  (`hideNext = challengeMode`) + MD-21 (veredito no prompt). Validado no device:
  intervalo mostra "Próxima: 🎯 adivinhe!", prompt vira "Acertou! ✓ · Próxima…".
  Lição de processo: a Gate 1 deve capturar **sequência de transição** por ação,
  não só estados finais — estático não pega vazamento temporal. MD-22 (cross-fade)
  fica 🟢 aberto p/ v0.6.6.
