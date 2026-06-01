# PLAN_CLAUDIO.md — Blueprint para a v0.6.0 (Educação & Interatividade)

E aí, Claudio! 

Como discutimos, o app já é performático e visualmente "Apple-grade". Agora o foco é transformar essa performance em **educação ativa**. O objetivo técnico desta release é aumentar a **interatividade cognitiva**: sair do modo "assistir" e entrar no modo "aprender por desafio e visualização semântica".

Abaixo, descrevo o storytelling técnico de como vamos implementar cada feature, do JNI ao Compose.

---

## 1. O Storytelling do Código: Pseudocódigo Sincronizado (VizActivity)

### O Problema
Hoje o usuário vê as barras se mexendo, mas não entende qual linha do C++ está causando aquele movimento.

### A Solução (The "How")
Vamos sincronizar o fluxo de `StepKind` (Compare, Swap, Set, Pivot) com um bloco de pseudocódigo renderizado no Compose.

1.  **Mapeamento Semântico:** No `VizActivity.kt`, criaremos um componente `PseudocodePanel(algoIdx, currentStepKind)`. 
2.  **JNI Extension:** No `VizEngine::fill`, hoje enviamos apenas `hi_a` e `hi_b`. Vamos expandir o buffer de snapshot para incluir o `last_step_kind` do `SingleEngine`.
3.  **UI Feedback:** Conforme o `StepKind` muda:
    *   `Compare` ➔ Destaque na linha `if (a[i] < a[j])`.
    *   `Swap` ➔ Destaque na linha `swap(i, j)`.
    *   `Pivot` ➔ Destaque na linha `p = partition(a, low, high)`.
4.  **Cognição:** Isso reduz a carga cognitiva, pois o cérebro do estudante mapeia instantaneamente o **efeito visual** (barras piscando) com a **causa lógica** (o código).

---

## 2. O Dojo de Escalonamento: Modo Desafio (SchedActivity)

### O Problema
O Escalonador é uma aula passiva. O usuário dá play e vê o gráfico de Gantt.

### A Solução (The "How")
Vamos implementar o **"Desafio do Próximo Passo"**.

1.  **State Machine:** Introduziremos um estado `ChallengeState` na `SchedActivity`.
2.  **Interação:** Quando o usuário clica em `Next Step`, se o modo desafio estiver ativo, a simulação pausa. O app pergunta: *"Qual tarefa o $algoIdx deve escolher agora?"*.
3.  **Validation:** 
    *   O usuário toca em uma `TaskPill`.
    *   Comparamos o `id` tocado com o `runAt[currentT]` que o motor C++ (`SchedBridge`) já precomputou.
4.  **Gamificação:** Acertos geram um "Pop" verde e um áudio de sucesso. Erros abrem o `HeuristicDialog` automaticamente, explicando a regra (ex: "No SJF, pegamos a de menor duração, não a que chegou primeiro").
5.  **Story:** O usuário deixa de ser espectador e vira o "Escalonador do Kernel".

---

## 3. O Mentor IA: Gemini Context-Aware (GeminiAssistant)

### O Problema
A explicação da IA hoje é genérica (sobre o algoritmo em si).

### A Solução (The "How")
Vamos passar o **estado atual da simulação** no prompt do Gemini.

1.  **Prompt Engineering:** Em vez de "Explique o QuickSort", enviaremos:
    > "O usuário está visualizando o QuickSort. O estado atual tem 96 elementos. Foram feitas 1500 comparações e 400 swaps. Explique por que, matematicamente, este cenário está sendo eficiente ou ineficiente."
2.  **Contexto do Sched:** No escalonador, enviaremos o JSON de `SchedResult` para a IA:
    > "O algoritmo é Round Robin com quantum 2. A tarefa T1 está sofrendo preempção constante. Explique para o usuário o conceito de overhead de troca de contexto baseado nesses dados reais."
3.  **Interatividade:** O Gemini deixa de ser uma Wikipedia e vira um monitor de laboratório que olha para a tela do aluno.

---

## 4. Design Semântico: Cores por Estado (Visual Consistency)

### O Problema
O arco-íris é bonito, mas não tem significado algorítmico.

### A Solução (The "How")
Implementar o modo de **Cores por Estado** no `barColor` do `VizActivity.kt`:

*   **Default:** `Color(0xFF8E75FF)` (Roxo suave).
*   **Active (hi_a, hi_b):** `Color.White` ou `Color.Yellow` (Destaque total).
*   **Sorted Region:** `Color(0xFF3DCF7A)` (Verde). O motor C++ precisa informar qual o índice `last_sorted` para o JNI.
*   **Impacto:** O estudante "vê" a mancha verde da região ordenada crescendo no Insertion Sort, ou as partições se formando no QuickSort.

---

## Resumo da Release v0.6.0
*   **Início:** Refatoração do buffer JNI para suportar metadados de passo e estado ordenado.
*   **Meio:** Implementação do Painel de Pseudocódigo e Modo Desafio no Compose.
*   **Fim:** Refinamento dos prompts do Gemini e polimento visual das cores semânticas.

Claudio, esse plano foca em **Rigor e Didática**. Quando o aluno fechar o AlgoViz, ele deve ter aprendido como o algoritmo funciona "na unha", não apenas ter visto luzes coloridas.

Mãos à obra! 🚀
