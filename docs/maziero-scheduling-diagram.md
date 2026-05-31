# Maziero scheduling diagram — rendering rules

Source of truth for how the **Scheduler trainer** mini-app (`SchedActivity`) draws
its timing diagram. Transcribed from Carlos Maziero, *Sistemas Operacionais:
Conceitos e Mecanismos*, Cap. 6 ("Escalonamento de tarefas"), figures 6.2
(Round-Robin), 6.4 (SJF) and 6.5 (SRTF). The earlier v1 used a single-row
"one cell per tick" ribbon — that is **not** Maziero's diagram and must not come
back.

## The diagram is a space-time (swimlane) chart, not a single ribbon

- **Y axis = tasks, one horizontal lane each.** Lanes are stacked with the
  **lowest id at the BOTTOM** (t1 bottom → tN top). Each lane is labelled with
  the task name (`t1`, `t2`, …) at the left, outside the plot.
- **X axis = time**, integer ticks `0..total`. The axis is drawn as an arrow
  pointing right, with a `t` label past its end. Tick marks + integer labels sit
  below the axis. The Y axis is an arrow pointing up.
- **Dark, app-native surface.** The chart lives on the app's dark panel, NOT a
  white "textbook page" (an earlier white-panel rendition was rejected for
  looking like a PDF pasted into the app). Grid hairlines, markers and the
  playhead carry the structure on dark.
- **Dotted vertical gridlines** at every integer time, spanning the plot height.

## Shipped rendering (v1) — the owner's-app convention

The live screen (`SchedActivity`) renders this as a replica of the owner's ImGui
app, not as the book's printed hollow-bar figure. Same lanes + semantics, but:

- **execution = a solid coloured cell** on each tick the task holds the CPU
  (per-task colour, rounded, glossy top); the **faint colour track** behind the
  lane spans arrival→finish, so the un-filled stretches read as *waiting* (the
  book's hollow part, in dark form);
- **▶ green = arrival**, **■ red = termination**, drawn as event markers on the
  lane (the book's bar edges, made explicit);
- a **spring-animated red/accent playhead** marks the current tick during
  step-through; ready tasks (arrived, not running) **glow** ("acendem"), both on
  the lane label and as pills below the chart;
- transport: ◀ step back · ▶ step · ⏩ run-to-complete · 🔄 reset.

The conceptual model below (hollow=waiting bar spanning arrival→finish) is the
book's print form and the source of truth for *what the metrics mean*; the
shipped renderer expresses the same thing in motion.

## Each task is ONE bar from arrival to completion, split into two states

For task *k*, draw a single rectangle in its lane spanning
**`[arrival_k, finish_k]`** (its turnaround window). Nothing is drawn before
arrival or after completion. Inside that bar, every time unit is one of:

- **Filled with the task's colour = RUNNING** (occupying the CPU that tick).
- **Hollow / white (outline only) = READY but WAITING** in the ready queue.

So the bar reads directly as the textbook metrics:

| Visual | Meaning |
|---|---|
| Left edge of the bar | **arrival** |
| Total bar length (left→right edge) | **turnaround** `Tt = finish − arrival` |
| Sum of the hollow segments | **waiting** `Tw = Tt − duration` |
| Sum of the coloured segments | **duration** (execution time) |
| Arrival → first coloured pixel | **response** `Tr = start − arrival` |

## Preemption shows as multiple coloured segments in one lane

Preemptive algorithms (RR, SRTF, PRIOp, PRIOd) run a task in several bursts.
Each burst is its own coloured segment; the hollow gaps between them are the
times the task sat in the ready queue waiting to be redispatched. Round-Robin
(fig 6.2) is the canonical example: t1 appears as blue segments at 0–2, 6–8,
13–14 with white gaps between.

## Per-task colour palette (sampled from the book figures)

Fixed colour per task index so it matches the textbook and stays stable between
the Gantt and the task table swatches:

| Task | Colour | Hex (ARGB) |
|---|---|---|
| t1 | blue   | `0xFF3169CF` |
| t2 | yellow | `0xFFD8D818` |
| t3 | purple | `0xFF9048C0` |
| t4 | green  | `0xFF48D830` |
| t5 | red    | `0xFFDF313B` |

For workloads with more than 5 tasks, extend with further distinct hues
(orange, teal, magenta, …) — the first five must stay as above.

## Data → drawing (from the `SchedBridge` JSON)

The bridge already emits everything needed:

- `tasks[].arrival` / `tasks[].finish` → the bar's left/right edges.
- `gantt[]` = `{time, task_id}` per executed tick → group by `task_id` to get
  each task's **running** ticks; every tick in `[arrival, finish]` that is *not*
  a running tick is **waiting** (hollow). (No I/O blocking in the reference
  workload, so ready-waiting is the only hollow state for now; a future
  blocked/I-O state would get its own hatch/shade.)
- A running tick at time *t* fills the cell `[t, t+1)` (one tick wide).

## Legend (shown under the chart)

> ▣ preenchido = executando · ☐ vazio = esperando (fila de prontas) · barra =
> chegada → término

This keeps the pedagogy explicit, the same way the book's surrounding text does.

## Textual basis (the encoding is the book's, verbatim)

The filled/hollow convention is exactly how Maziero defines it in the FCFS
section (Cap. 6, §6.4.1): *"Os quadros sombreados representam o uso do
processador (observe que em cada instante apenas uma tarefa ocupa o
processador). Os quadros brancos representam as tarefas que já ingressaram no
sistema e estão aguardando o processador (tarefas prontas)."* — i.e. **shaded =
running on the CPU, white = ready and waiting in the queue**. Our renderer is a
direct transcription of that.

The book draws this same diagram for every algorithm: figs 6.1 (FCFS), 6.2 (RR),
6.4 (SJF), 6.5 (SRTF), 6.6 (PRIOc), 6.7 (PRIOp), 6.8 (PRIOd) — so the single
renderer above is correct for all seven the trainer exposes.
