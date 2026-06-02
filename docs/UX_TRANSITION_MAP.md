# UX_TRANSITION_MAP — mapa de transições & validação UX/IHC avançada

Documento-fonte para a **validação UX/IHC completa** do algoviz. Produzido por
agentes críticos (lente HCI: Norman, Nielsen, Material Motion, Dan Saffer) sobre
o **estado atual** do código, embasado em pesquisa. É o próximo passo natural do
app: trazer as *transições* (não só os estados) ao mesmo nível Apple-grade que o
conteúdo das telas já tem. Companion da skill `validate-before-ship` e do
`docs/VALIDATION_LOG.md`.

Severidade: 🔴 crítico (quebra affordance / deixa o usuário sem próximo passo) ·
🟡 médio (fricção, corte seco onde caberia motion) · 🟢 polish.

---

## 1. Glossário owner → termo HCI (interpretações confirmadas)

O dono descreve UX em linguagem natural; abaixo, os termos que ele usou e a
tradução para o vocabulário HCI que se confirmou correta na prática (cada uma
gerou um achado real). Use esta tabela para alinhar a comunicação nas próximas
rodadas.

| O que o dono disse | Termo HCI / fonte | Confirmação |
|---|---|---|
| "existe a tela ensinando a **arrastar**?" | **Affordance vs. signifier do gesto** (Norman) — tap vs. drag | ✅ o crítico achou risco real de tap-vs-drag nas pills do Desafio (sem signifier do gesto) |
| "temos o **tutorial forçado**?" | **Forced onboarding / first-run experience** (onboarding-cro) | ✅ Min Cash Flow força; Scheduler não tinha → virou o `ChallengeIntroSheet` |
| "depois que clico, a **opção que tenho depois da ação é clara? ou não fica explícito**?" | **Feedforward** + **gulf of evaluation** (Norman; Djajadiningrat) | ✅ revelou o vazamento "Próxima: t3" entre perguntas e a instrução obsoleta no feedback |
| "viu as **transições de cada ação**?" | **State transitions / Material Motion**; "moment of completion" (Saffer) | ✅ este mapa — quase toda transição de estado de UI é corte seco |
| "**esforço mental** por muito **acúmulo de botões** ou desorganizado" | **Carga cognitiva** (Sweller); **progressive disclosure**; **lei de Hick** | ✅ 1ª auditoria → reorganização do painel do Sort (35→~10 visíveis) |
| "alguém ficando **bastante tempo usando**" | **Retenção / time-on-task / engajamento / "aha moment"** (onboarding-cro) | ✅ orientou o foco em onboarding e payoff |
| "ele é **muito exigente** / **ex-Apple**" | **Apple HIG bar**; tap target 44pt; motion contínuo | ✅ o re-review ex-Apple pegou os 🔴 que os estáticos não pegaram |
| "**eles** vão criar o **mapa completo**" | Agentes críticos como **reviewers** (heuristic evaluation) | ✅ este documento |

---

## 2. Critérios avançados de validação de transições (embasados em pesquisa)

### 2.1 Os 4 padrões do Material Motion (qual usar quando)
Toda mudança de conteúdo/navegação deveria escolher **um** destes em vez do corte seco:

| Padrão | Quando usar | Onde falta no algoviz (ex.) |
|---|---|---|
| **Container transform** | dois elementos com **continuidade de forma** (um vira o outro) | Home tile → mini-app; grafo do Racha → grafo idêntico do Extremo |
| **Shared axis** (X/Y/Z) | elementos com relação **espacial/navegacional** (irmãos, passos) | abas do Racha (Saldos/Acerto/Grafo); painel Som & Visual abrir/fechar |
| **Fade through** | conteúdos **sem relação forte** trocando no mesmo lugar | Sort Single↔Race no canvas; output do Profiler "rodando"→JSON |
| **Fade** | elemento **entra/sai** dentro da tela | ChallengePrompt aparecendo; PayoffBanner; sheets/dialogs |

### 2.2 Microinteração (Dan Saffer) — toda transição importante tem 4 partes
**Trigger → Rules → Feedback → Loops/Modes.** O gap recorrente no algoviz é o
**Feedback** (o "moment of completion") e o sinal de **Mode**: ações mudam o modo
(draw, desafio, rodando) sem comunicar a mudança.

### 2.3 Feedforward vs. feedback
- **Feedforward** = antes/durante a ação, comunicar *o que vai acontecer* e *qual o próximo passo*.
- **Feedback** = depois da ação, confirmar *o que aconteceu*.
- **Gulf of execution** = o usuário sabe como agir? **Gulf of evaluation** = o usuário entende o resultado?

### 2.4 Checklist obrigatório de transição (por ação) — entra na Gate 3 do pipeline
Para cada ação interativa, validar com **rajada de frames** (tap → +200ms → +500ms → +1s):
1. **Sinal de modo**: se a ação muda o modo, a tela mostra isso (não só o ícone do botão)?
2. **Moment of completion**: ao terminar, há um sinal claro (flash/pulse/haptic/texto)?
3. **Feedforward do próximo passo**: a próxima opção do usuário fica explícita?
4. **Continuidade**: a troca usa um dos 4 padrões Material, ou é corte seco?
5. **Sem vazamento temporal**: nenhum estado intermediário revela info que devia estar oculta (ex.: a resposta de um desafio).
6. **Affordance honesta**: controles inativos parecem inativos (não "mortos" mas clicáveis).

---

## 3. Mapa completo de transições (estado atual, por mini-app)

> Consolidado de 4 agentes críticos lendo o código. Achado-fato de leitura: **não
> há nenhum `AnimatedVisibility`, `Crossfade`, `AnimatedContent`, `animateItemPlacement`
> nem `ActivityOptions`/shared-element em nenhuma Activity** — toda a animação vem
> de (a) componentes M3 com motion embutido, e (b) os loops `withFrameNanos` que
> redesenham os Canvas. Logo, **toda transição de composição/navegação é corte seco.**

### 3.0 Global / Home
- **TR-H1** 🟡 Home tile → mini-app: `startActivity(Intent)` puro, sem container transform — a tela nova não nasce do tile. Pervasivo (todos os 6 mini-apps são Activities separadas, não Compose Navigation).
- **TR-H2** 🟡 AutoCloseGuard: gesto de 10 toques sem **feedback parcial** (nada indica "3/10"); ao desativar, o botão **some sem confirmação** ("Fechamento desativado por esta sessão").

### 3.1 Sort Visualizer
- **TR-S1** 🔴 **Draw mode invisível**: entrar/sair de draw só muda o ícone do botão; o **canvas não muda de aparência** (sem borda/tint/cursor) → o mesmo gesto (drag) muda de significado sem sinal de modo.
- **TR-S2** 🔴 **Drag de velocidade sem feedback**: arrastar horizontal muda a velocidade, mas **nada na cena confirma** (sem overlay "2×"); gesto oculto + zero feedback.
- **TR-S3** 🟡 **Auto-loop teleporta**: após o finish flash (clímax bom), o array salta de ordenado→embaralhado em corte seco — quebra a continuidade do loop ambiente (caberia fade through).
- **TR-S4** 🟡 Single↔Race: troca de layout do canvas **e** do painel ao mesmo tempo, abrupta (caberia fade through no canvas).
- **TR-S5** 🟡 Painel Som & Visual / Hide: abre/fecha por corte seco (salto de altura/layout; caberia `AnimatedVisibility`/shared-axis).
- **TR-S6** 🟡 Double-tap p/ pausar e tap-na-barra (nota) **sem feedback visual** no ponto tocado.
- ✅ Bom: finish flash com double-strike, VHS rewind temático, springs do M3 — o clímax é animado.

### 3.2 Scheduler + Modo Desafio
- **TR-D1** 🔴 **Transport "morto" durante a pergunta**: ▶/Executar viram no-op mas continuam com aparência ativa (sem dim); **◀ não tem guard** e ainda mexe `currentT`, podendo dessincronizar o "Tick N" do prompt.
- **TR-D2** 🔴 **Vão de feedforward intro→1º prompt**: "Começar ▶" fecha a sheet e **nada acontece** — o 1º prompt só nasce ao apertar Executar; a sheet não aponta esse próximo passo.
- **TR-D3** 🟡 **Desafio todo em corte seco**: ChallengePrompt aparece/some e as cores das pills (verde/vermelho/✓/✗) trocam **instantâneo** (sem `animateColorAsState`, ao contrário do accent/chips que animam); a cada ciclo o layout "pisca".
- **TR-D4** 🟡 **Reativar desafio é silencioso** (2ª+ vez): só o ✓ no chip muda; até a 1ª pausa a tela parece o modo normal.
- **TR-D5** 🟡 Troca de algoritmo durante pergunta: o prompt **some sem explicação**; chips ficam clicáveis durante a pergunta (fácil disparar sem querer).
- **TR-D6** 🟡 Fim do desafio = simulação comum, **sem tela de resumo** ("acertou X de Y — recomeçar?"); placar não zera entre execuções (só em troca de algoritmo) → razão X/Y acumula.
- ✅ Já maduro em **legibilidade** (signifiers "👆 Toque…", dim das não-candidatas, ✓/✗ não-só-cor, placar fora do toggle, veredito "Acertou!/Próxima…", "Próxima: 🎯 adivinhe!"). Falta a **camada de motion**.

### 3.3 Racha
- **TR-R1** 🔴 **Chip "✨ caso extremo" muda de significado em corte seco**: 1º toque troca 4→20 pessoas e **reprograma o chip** para gateway, sem nenhum motion nem ênfase — a maior mudança de estado+affordance do Racha passa despercebida.
- **TR-R2** 🟡 Abas Saldos/Acerto/Grafo: conteúdo troca em corte seco (caberia shared axis X).
- **TR-R3** 🟡 Racha → ExtremeActivity: abre um **anel de grafo idêntico** numa Activity nova com transição genérica — caso-livro de container transform desperdiçado.
- ✅ Bom: cascata animada dos SettlementCards (stagger 70ms); colapso automático Direto→Simplificado (900ms) que demonstra o algoritmo; barras divergentes com spring.

### 3.4 Min Cash Flow / Extremo
- **TR-M1** 🔴 **Fim = beco sem saída**: após o clímax de 4 atos, o PayoffBanner entra em corte seco, **o auto-play morre e não há CTA** ("↻ rever", "voltar ao Racha"); o banner cobre o centro do grafo.
- **TR-M2** 🟡 PayoffBanner sem scale-in (clímax merecia entrada com mola).
- **TR-M3** 🟡 Fechar tutorial → animação começa em corte seco (sheet some instantânea).
- **TR-M4** 🟡 Scrubber: pular para frente **não dispara floaters/raios** (só o avanço incremental os gera) — quem usa o scrubber perde o "tempero"; ◀ também é mudo (sem "des-cancela").
- **TR-M5** 🟡→🔴 (estética/coreografia — **o clímax visual do app**) **A revelação das 190 dívidas é preguiçosa e previsível.** Hoje: cada aresta aparece **inteira de uma vez** (`drawEdge` traça o bézier completo, ExtremeActivity L424), na **ordem sequencial** de `r.direct` (pares i<j, DebtReduction L92), e a poda remove na mesma ordem (L418) → "dívidas indo do nó pro vizinho ao lado", sem vida. **Spec do redesign:**
  1. **Draw-on das arestas (Trim Path):** cada dívida **cresce da origem ao destino** — a ponta parte de `pos[from]` e *chega* em `pos[to]` em ~350–450ms (emphasized-decelerate). Técnica: `PathMeasure.getSegment(0, len·progress)`; cada aresta tem `birthFrame → progress`; o loop `withFrameNanos` já existe.
  2. **Ordem aleatória:** embaralhar a sequência de Build (seed fixo p/ reprodutibilidade) — as arestas **pipocam** pelo grafo conectando **extremos opostos** (cruzando o centro), não vizinhos em sequência. É o "parecer natural" que o dono pediu.
  3. **Poda dinâmica:** ao pagar/absorver, a aresta **encolhe de volta à origem + fade** (não some instantânea) — "conforme paga, some do grafo".
  4. **Escala pela estética (autorizado pelo dono — "a estética vale"):** se 190 arestas crescendo ficar poluído, **reduzir o grafo completo para ~14–15 pessoas (C=91–105 ≈ "100 passos")** via `extremeDemo(n)`. Trade-off: o headline "190→1" vira "~105→1" (atualizar Códex/CLAUDE.md). Recomendação: testar 15 pessoas; priorizar a legibilidade do crescimento.
  - **Onde:** `ExtremeActivity.kt` drawReductionFrame (L410-437) + `drawEdge` (L500); `DebtReduction.kt` buildReduction (ordem) + extremeDemo (n). **Esforço:** M–G. **Impacto:** **Alto** (é o "uau" do app; o "addictive" depende disto).
- ✅ **Referência do app**: scrubber tricolor = feedforward estrutural; **auto-pause no "grafo cheio" com caption "▶ para simplificar"** = Norman impecável; floaters/raios/flash dão vida a cada passo.

### 3.5 Profiler
- **TR-P1** 🔴 **Run → resultado sem "moment of completion"**: o texto-guia é destruído ao iniciar, o JSON materializa de uma vez (corte seco), e **nada sinaliza que terminou** (sem check/flash/haptic). Candidato canônico a fade through.
- **TR-P2** 🔴 **Resultado → "Enviar" sem feedforward**: a única dica de "agora envie" é o botão passar de disabled→enabled, **acima** da área onde o olho está (o output, embaixo).
- **TR-P3** 🟡 URL nova entra no histórico sem `animateItemPlacement`; muda em duas regiões distantes (topo+fundo) sem ligação visual.
- **TR-P4** 🟡 Chip de benchmark preenche o campo de filtro sem destacar o efeito colateral; progress é indeterminado numa suíte de fases conhecidas.
- **TR-P5** 🟡 Crash anterior entra como texto monoespaçado igual ao resto (evento de alta severidade sem cor de alerta/scroll-to).

### 3.6 Códex
- **TR-C1** 🔴 **Navegação por seção sem "você está aqui" + NavIndex que some**: o chip §N não fica selecionado, a seção-alvo não se destaca ao chegar, e o `NavIndex` **rola para fora** (é item da LazyColumn) → após o 1º scroll o usuário perde o único meio de navegar.
- **TR-C2** 🟡 Documento longo sem scrollbar/indicador de seção ativa/voltar-ao-topo.
- **TR-C3** 🟢 Emblema corvo+chave totalmente estático (oportunidade de "vida" desperdiçada).

---

## 4. Tabela mestra priorizada (para validar com os críticos)

| ID | Sev | Mini-app | Transição | Recomendação (padrão Material / feedforward) |
|---|-----|----------|-----------|-----------------------------------------------|
| TR-S1 | 🔴 | Sort | draw mode invisível | tint/borda no canvas + cursor enquanto em draw (sinal de modo) |
| TR-S2 | 🔴 | Sort | drag-velocidade sem feedback | overlay efêmero "2×" + signifier do gesto |
| TR-D1 | 🔴 | Sched | transport morto + ◀ vaza na pergunta | dim 0.32 nos 3 botões durante pendência + guard no ◀ |
| TR-D2 | 🔴 | Sched | vão intro→1º prompt | "Começar ▶" dispara `playing=true` ou aponta "toque Executar" |
| TR-R1 | 🔴 | Racha | chip "caso extremo" muda significado | animar entrada dos 20 nós + pulse/scale no chip ao virar gateway |
| TR-M1 | 🔴 | Min Cash Flow | fim = beco sem saída | CTA "↻ rever / voltar ao Racha" + tirar banner do centro |
| TR-P1 | 🔴 | Profiler | Run→resultado sem completion | fade through no output + sinal de conclusão (pulse/haptic) |
| TR-P2 | 🔴 | Profiler | resultado→Enviar sem feedforward | puxar o CTA Enviar para o foco (junto/abaixo do output) |
| TR-C1 | 🔴 | Códex | nav §N sem "você está aqui" | NavIndex sticky + chip ativo sincronizado + highlight na seção |
| TR-D3 | 🟡 | Sched | desafio sem motion | `AnimatedVisibility` no prompt + `animateColorAsState` nas pills |
| TR-R2/R3 | 🟡 | Racha | abas + salto p/ Extremo | shared axis nas abas + container transform Racha→Extremo |
| TR-S3/S4/S5 | 🟡 | Sort | loop/Single-Race/painel | fade through / shared-axis nos cortes secos |
| **TR-M5** | 🟡→🔴 | Min Cash Flow | revelação preguiçosa das 190 dívidas | **draw-on (Trim Path)** crescendo da origem + ordem aleatória + poda dinâmica; reduzir p/ ~105 se preciso |
| TR-M2/M3/M4 | 🟡 | Min Cash Flow | banner/tutorial/scrubber | scale-in no banner; floaters no scrub |
| TR-H1 | 🟡 | Global | Home→mini-app | container transform (ou aceitar como débito de arquitetura: Activities) |
| TR-P3/P4/P5, TR-C2 | 🟡 | Profiler/Códex | histórico/chip/crash/scroll | animateItemPlacement; alerta no crash; indicador de progresso |
| TR-H2, TR-S6, TR-D4/D5/D6, TR-C3 | 🟢 | vários | feedbacks/polish menores | ver §3 |

**Contagem:** 9 🔴 · ~13 🟡 · ~5 🟢 de transição. Nenhum bloqueia uma release de
*correção* (a v0.6.5 já está no ar), mas são o backlog do **próximo eixo do app**:
levar as transições ao nível do conteúdo.

---

## 5. Princípios de IHC educacional & engajamento viciante (ético)

> Pesquisa guardada a pedido do dono — "coisas super interessantes... ainda que
> seja viciante". O algoviz quer ser **educacional E addictive/ASMR**; estes
> princípios mostram que os dois objetivos são o **mesmo** quando ancorados no
> aprendizado, não em manipulação. Cada item traz: o achado, por que é
> super-interessante, e como aplica ao algoviz (✅ já temos / 💡 oportunidade).

### 5.1 Flow channel — desafio LEVEMENTE acima da habilidade (Csikszentmihalyi)
O flow (foco intenso, prazer, perda da noção do tempo) acontece numa faixa estreita:
o desafio precisa **superar um pouco** a habilidade atual. Fácil demais = tédio;
difícil demais = ansiedade. **Super-interessante:** isso dá uma régua objetiva para
dificuldade — não "difícil" nem "fácil", mas *adaptativo ao usuário*.
- 💡 **Modo Desafio**: começar no FCFS (trivial) e escalar para SRTF/PRIOd conforme o aluno acerta — dificuldade adaptativa, mantendo-o no canal de flow.
- 💡 **Sort/Min Cash Flow**: ajustar `size`/`speed` ou nº de pessoas ao histórico do usuário.

### 5.2 Desirable difficulties — o que ATRAPALHA agora ensina mais (Bjork & Bjork)
Estratégias que **pioram o desempenho imediato** produzem **retenção e transferência
muito melhores** a longo prazo: **testing effect** (recuperar > reler), **spacing**
(distribuir no tempo), **interleaving** (misturar tipos em vez de blocos).
**Super-interessante e validador:** *performance ≠ learning* — então "punir o erro"
no Desafio **não é fricção ruim, é o mecanismo de aprendizado**. Justifica o recurso.
- ✅ **Testing effect** já é a alma do Modo Desafio (prever > assistir).
- 💡 **Interleaving**: um modo "misto" que alterna algoritmos a cada pergunta (em vez de um algoritmo por vez) — comprovadamente melhor que blocar.
- 💡 **Spacing**: trazer de volta um algoritmo visto há sessões (puxa o [[feedback-validation-pipeline]] de retenção da onboarding-cro).

### 5.3 Hook Model — trigger → ação → recompensa variável → investimento (Nir Eyal)
O laço que forma hábito. **Recompensa variável** é o motor: a imprevisibilidade
prende (recompensas "da Caça" = informação/novidade; "da Tribo" = social).
**Super-interessante:** num app **solo** e educacional, a recompensa certa é a
**da Caça** (cada execução revela um padrão novo), não badges sociais ocos.
- ✅ **Recompensa variável já existe**: cada shuffle do Sort gera um padrão/arco-íris diferente; o finish flash é a recompensa. O grafo de 190→1 é a "caça" pela revelação.
- 💡 **Investimento**: streaks de acerto no Desafio, "coleção" dos 8 sorts dominados — pequenos depósitos que fazem o usuário voltar.
- ⚠️ **Ética (Manipulation Matrix de Eyal)**: só "viciar" no que **melhora a vida do usuário**. Num app que ensina, isto é fácil de justificar — ancorar sempre em "aprendi algo" (como o Duolingo ancora em Accomplishment), nunca em engajamento vazio.

### 5.4 Juiciness / Game feel — feedback "excessivo" em relação ao input (CHI 2024)
"Juicy" = quantidade generosa de feedback imediato por toque. Pesquisa do CHI 2024
mostra que isso motiva via **curiosidade, competência e *effectance*** (a sensação
de que minhas ações têm efeito no mundo). **Super-interessante:** é a base científica
do que já torna o algoviz gostoso — e prova que **vale espalhar para os pontos mortos**.
- ✅ **Já é um diferencial**: ASMR pentatônico, finish flash com double-strike, floaters/pops do Min Cash Flow, raios. Isso É o "viciante".
- 💡 Aplicar juiciness exatamente nos **cortes secos** da §3/§4 (veredito do Desafio com scale-stamp no ✓, conclusão do benchmark com pulse, PayoffBanner com mola).

### 5.5 Zeigarnik effect — o inacabado puxa de volta (open loops)
Tarefas **incompletas** são lembradas melhor e criam tensão que pede fechamento.
**Super-interessante:** transforma "fim" em "gancho" — um resumo que mostra o que
**falta** retém mais que um "parabéns, acabou".
- 💡 Conecta direto aos achados **TR-M1 / TR-D6** (fins em beco sem saída): em vez de só "190→1", fechar com "Você dominou 3 de 8 sorts — próximo: Heap?" → open loop.
- 💡 Progresso de coleção visível na Home (6 mini-apps, 8 sorts, 7 algoritmos de escalonamento) com lacunas a preencher.

### 5.6 Peak-end rule — julgamos pelo pico e pelo fim (Kahneman)
A memória de uma experiência é dominada pelo **momento mais intenso** e pelo **final**.
**Super-interessante e acionável:** o algoviz tem **picos** excelentes (finish flash,
payoff 190→1) mas **finais fracos** (beco sem saída). Melhorar o *end* tem retorno
desproporcional na percepção geral — e é barato.

### 5.7 Microinteração ensina o uso (Saffer)
Cada feedback sutil é "uma conversa quieta entre o sistema e o usuário" que **ensina
o comportamento** sem texto. Reforça §2.2: investir em feedback de transição não é só
estética — é **pedagogia de interface**.

---

## 6. Próximos passos
1. **Validar este mapa com o ex-Apple** (rodada dedicada a transições; já iniciada — os 🔴 TR-* saíram dessa lente).
2. Priorizar os **9 🔴 de transição** para v0.6.6+ (são gulfs de affordance/feedforward, maior retorno).
3. Atacar os **finais** (peak-end + Zeigarnik): transformar TR-M1/TR-D6 em open loops com CTA.
4. Adotar os 4 padrões Material Motion como **idioma padrão** + juiciness nos cortes secos.
5. A Gate 3 do `validate-before-ship` agora exige o **checklist §2.4** + rajada de frames por ação.

## Fontes (pesquisa)
**Transições / motion:**
- [Material Design 3 — Transitions](https://m3.material.io/styles/motion/transitions) · [The motion system (M2)](https://m2.material.io/design/motion/the-motion-system.html) · [Navigation transitions](https://m2.material.io/design/navigation/navigation-transitions.html)
- [Building Beautiful Transitions with Material Motion for Android (codelab)](https://developer.android.com/codelabs/material-motion-android)
- [Dan Saffer — Microinteractions (trigger / rules / feedback / loops & modes)](https://thedecisionlab.com/reference-guide/design/microinteractions) · Norman, *The Design of Everyday Things*; Djajadiningrat et al. — feedforward.
- **Animação de grafo (TR-M5):** [Data-driven animations design space — "Ant" (TVCG 2021, PDF)](https://deardeer.github.io/pub/TVCG21_Ant.pdf) (gradual appearance, geometry deformation) · [Animating edges — React Flow](https://reactflow.dev/examples/edges/animating-edges) · Trim Paths / draw-on (After Effects / Lottie / Compose `PathMeasure.getSegment`).

**IHC educacional & engajamento (§5):**
- [Nir Eyal — Hook Model / Optimize App Retention](https://medium.com/googleplaydev/optimize-app-retention-with-the-hooked-model-a0781f8e5d29) · [Hook Model + Octalysis (Yu-kai Chou)](https://yukaichou.com/gamification-analysis/hook-model-octalysis-habit-addiction/)
- [How Juicy Game Feedback Motivates — Curiosity, Competence, Effectance (CHI 2024)](https://dl.acm.org/doi/10.1145/3613904.3642656) · [Designing Game Feel: A Survey (arXiv)](https://arxiv.org/pdf/2011.09201)
- [Zeigarnik effect (open loops)](https://en.wikipedia.org/wiki/Zeigarnik_effect)
- [Flow Theory in learning (Csikszentmihalyi)](https://www.structural-learning.com/post/flow-state) · [Bjork — Desirable Difficulties (guia)](https://www.structural-learning.com/post/robert-bjork-teachers-guide-desirable) · [Bjork & Bjork (2011) — "Making things hard on yourself, but in a good way"](https://www.unh.edu/teaching-learning-resource-hub/sites/default/files/media/2023-06/itow-introducing-desirable-difficulties-into-practice-and-instruction-bjork-and-bjork.pdf)
