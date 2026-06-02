# RELEASE_PLAN — plano de implementação (validado UX/IHC)

Plano executável consolidado a partir de `UX_STUDY.md`, `UX_TRANSITION_MAP.md`,
`VALIDATION_LOG.md` + uma rodada de **validação UX/IHC** (heurística completa) e
um **plano técnico de arquitetura**. Fase de planejamento — nada implementado.
`versionCode=17 / versionName=0.6.5` na base.

## Veredito da validação
O plano é o melhor mapa de **transições/semântica/engajamento** do repo (TM-1, MD-23,
finais-como-open-loops confirmados no código e na literatura). **Mas era cego para a
malha de estados de produto** (vazio/erro/loading/offline/settings/persistência/
destrutivas/i18n/a11y) e **deixou passar uma feature de IA já no APK** (Gemini em
`VizActivity` + `ai/GeminiAssistant.kt`) que **viola Nielsen #9 hoje**. Aprovado como
polish; só vira "completo" com os STD-* abaixo.

## 3 correções de rumo (obrigatórias antes de seguir)
1. **Persistência (`object Progress`) sobe para a 1ª release.** O app tem ZERO persistência;
   sem ela, "recompensa viciante/streak/coleção" é **retenção falsa** (estado morre no `onDestroy`).
2. **Tratar como corretude/privacidade, não polish:** STD-1 (Gemini erro×loading×sucesso),
   STD-6 (moeda/headline hardcoded — o "Mário deve R$67 a Cássia" é string fixa que **ignora o
   resultado real** = bug), STD-9 (upload paste.rs posta **público sem confirmar**).
3. **TR-D6 esconde um bug:** `challengeScore/Total` só zeram em troca de algoritmo (não em
   `onReset`/fim) → placar acumula "🎯 8 de 6". Marcar como bug, junto de TR-D1.

## STD-* — lacunas de produto adicionadas (heurística completa)
| ID | Sev | Lacuna | Recomendação |
|---|-----|--------|--------------|
| STD-1 | 🔴 | Gemini/IA invisível ao plano; erro colapsado no diálogo de sucesso; loading sem spinner/cancelar/timeout; sem offline | separar erro×loading×sucesso (ícone/cor/retry/cancelar); detectar rede; "sem API key" como onboarding |
| STD-2 | 🔴 | Estados vazios ausentes (Racha pessoas/despesas/grafo n=0 → tela preta; histórico do Profiler) | empty-state = ilustração+frase+CTA; tratar `n==0` no Canvas |
| STD-3 | 🔴 | Recuperação de erro/retry inexistente (Scheduler `error` sem botão; jobs/upload/Gemini) | padrão único "msg humana + Tentar de novo [+reportar]" |
| STD-6 | 🔴 | i18n: só `app_name`; **moeda/número hardcoded pt-BR**; headline do Extremo string fixa que ignora o `Settlement` real | `NumberFormat`; `values-en`; parametrizar headline (conserta bug). Sobe BT-2 |
| STD-9 | 🔴 | Sem undo/confirmação (Nielsen #3); **upload posta público sem confirmar** | Snackbar-undo; **diálogo "isto será público" + anonimização** (sobe BT-6) |
| STD-4 | 🟡 | Loading/skeleton só no Profiler; Scheduler mostra `"…"`; Gemini sem progresso | skeleton real no 1º load + Gemini |
| STD-5 | 🟡 | Offline não detectado (paste.rs, Gemini) | checar conectividade; mensagem específica |
| STD-7 | 🟡 | A11y: Canvas-herói mudos ao TalkBack; fontScale grande nunca testado; `TXT_DIM`<AA 4.5:1; alvos <48dp (chip ✨, ℹ 32dp, GlassIcon) | semântica dinâmica nos Canvas; contraste; fontScale 2.0; alvos ≥48dp |
| STD-8 | 🟡 | Responsividade não sistematizada; rails em larguras mágicas (300/360dp); sem tablet-portrait | `WindowSizeClass`; validar tablet 2 orientações |
| STD-10 | 🟡 | Sem tela de Settings e ZERO persistência (auto-close/tutoriais/som/idioma em memória) | Ajustes via DataStore; lar do `Progress`. **Subir** |
| STD-11 | 🟡 | Sem design system: paleta/tipografia/spacing duplicados em 5 Activities (divergem); GraphDraw duplicado | centralizar tokens + grafo (pré-req de MD-23) |
| STD-12 | 🟡 | Onboarding global ausente: Home é lista plana, sem "comece aqui", sem arco Racha→Extremo | boas-vindas + ordem sugerida + ligação narrativa (casa com §4.1) |
| STD-13 | 🟢 | Navegação inconsistente: cada mini-app inventa cabeçalho | cabeçalho/voltar padrão |
| STD-14 | 🟢 | Jank latente: `Ledger()` recriado por recomposição; `measure()` por frame | `remember`-izar derivados; cachear `TextLayoutResult` (vira 🟡 no "modo 50 pessoas") |

## Fundação F0 (destrava tudo — fazer primeiro)
- **F0.a `Progress.kt`** (SharedPreferences, local-only, sem PII): `sortsMastered/schedulersMastered`,
  `recordChallenge/accuracy/last5` (janela de flow), `sessionDays` (D1/D7, reusa o relógio do AutoClose),
  `challengeIntroDone/extremeTutorialDone` (resolve MD-18), `resetAll()`. Init em `AlgovizApp.onCreate`.
  `ChallengeIntro.dismissed`/`ExtremeTutorial.dismissed` viram delegações. **CA:** matar e reabrir → persiste; XML só com escalares (sem PII).
- **F0.b `Motion.kt`**: tokens (Standard 250 / Emphasized 450 / easings / springStd 0.7 / springBouncy 0.45). Adoção oportunística. **CA:** novos motions referenciam `Motion.*`.
- **F0.c `GraphDraw.kt`**: extrair `drawCurvedEdge(... progress: Float = 1f)` + `drawAmountChip` + `ringLayout` (BT-1 — Racha e Extremo duplicam). O `progress` já é o gancho do Trim Path (TR-M5). **CA:** Racha/Extremo pixel-equivalentes com `progress=1f`.
- **F0.d `ModeSkin`**: `WatchSkin` (azul atual) / `ChallengeSkin` (quente vibrante) — pré-req do MD-23. **Regra (efeito colateral do crítico):** o accent do modo Desafio **não pode** ser vermelho nem colidir com `TASK_COLORS`; verde/vermelho ficam reservados ao veredito.

## Fases (ordem por dependência/risco)
- **FASE 1 (v0.6.6) — corrigir o que CONTRADIZ** [paralelos]: **TM-1** (playhead — Opção A esquerda ou B centro + legenda) · **TR-D1** (guard no `onBack` = bug + dim 0.32) · **TR-D2** ("Começar ▶" dispara `playing=true`, avançando ao 1º ponto de decisão). **+ corretude/privacidade:** STD-1, STD-6 (moeda+headline), STD-9 (upload-confirm), F0.a Progress, TR-D6-bug.
- **FASE 2 (v0.6.7) — Modo Desafio real + Finais:** **MD-23** (segmented control Assistir/Desafio + skin imediata + faixa de modo + recompensa suculenta, absorve TR-D3/D4/MD-22) — depende de F0.a/F0.d/STD-11 · **TR-M1+TR-D6** (finais com CTA/open-loop) — depende de F0.a.
- **FASE 3 (v0.7.0) — Coleção & juiciness** [Home‖Profiler‖Códex]: Home-hub (§4.1) · juiciness P0 · **TR-P1/P2** · **TR-C1**. + STD-2/3 (empty/error) quando entrar edição/delete do Racha.
- **FASE 4 (v0.7.x) — Motion idioma + clímax:** **TR-M5** (draw-on/Trim Path — maior risco de frame-budget) · TR-S1/S2/S3/S4/S5 · TR-R1/R2/R3 · TM-2/TM-3 · STD-7 (a11y pass) · STD-8 (WindowSizeClass).

## Riscos técnicos (do arquiteto)
1. **Headline "190→1" tem blast radius:** `HomeActivity`, `PayoffBanner` (hardcoded), `ExtremeTutorial` (190 literal), CLAUDE.md, DocsActivity — e **`DebtReductionTest.kt` tem `assertEquals(190)` em 5 asserts → reduzir quebra os host tests (CI)**. Mitigar: parametrizar o teste por `n`, ou manter 190 e só renderizar subconjunto. Decidir com o dono.
2. **TM-1 afeta o "Tick N" do Desafio** (`nextTick = currentT+1`) — mudar p/ centro é só visual, mas validar o Desafio logo após.
3. **Frame-budget:** ~105–190 arestas com `PathMeasure.getSegment`/frame no N975F (Exynos 9825) — só animar arestas *em nascimento*, cachear `PathMeasure`, cair p/ `n=15` se `gfxinfo` >16ms.
4. **Persistência × AutoClose 39min:** `Progress` grava escalares em eventos discretos; reusar o cronômetro de foreground do AutoClose, não criar segundo timer.
5. **MD-23 skin quente × WCAG/feedback:** verde/vermelho do veredito têm de sobreviver sobre fundo quente (Gate a11y).

## Validação (validate-before-ship — Gates 0–4 + 3.5)
Uma tag por fase; cada uma roda o pipeline e **acrescenta** ao `VALIDATION_LOG`. Não lançar área com 🔴 aberto.
- **Gate 0:** build/install (+ `:app:testDebugUnitTest` se TR-M5 mexer em `n` — os `assertEquals(190)`).
- **Gate 1:** touch no N975F + **rajada de frames por ação** (tap→+200ms→+500ms→+1s) — sequências, não estados finais.
- **Gate 3 + 3.5:** checklist de transição (§2.4) + **semântica de representação** (TM-1/2/3 contra a now-line).
- **Gate 4:** Nielsen/Norman + a11y (contentDescription, contraste da skin, foco).
- **Ética:** maestria só por desempenho; streak informativo; `Progress` local-only com "zerar"; teto 39min.

## Escopo proposto da PRÓXIMA release (v0.6.6 redefinida) — mínimo-completo
**Fundação + corretude + os 🔴 baratos que destravam o resto:** F0.a Progress · F0.b Motion ·
F0.c GraphDraw · F0.d ModeSkin · **TM-1** · **TR-D1** (+bug) · **TR-D2** · **TR-D6-bug** ·
**STD-1** (Gemini 3-estados) · **STD-6** (moeda+headline) · **STD-9** (upload-confirm).
Fica para v0.6.7+: MD-23, finais, coleção, TR-M5, a11y/responsividade pass.
