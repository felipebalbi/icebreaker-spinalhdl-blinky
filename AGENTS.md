# AGENTS.md

Conventions for AI agents (Copilot, Codex, Cursor, Aider, …) and human
contributors working in this repository. Per-project specifics live in
`<Project>/AGENTS.md` and override anything below.

## Repo overview

Bottom-up SpinalHDL bring-up examples for the **iCEbreaker**
(Lattice iCE40-UP5K) board. Each top-level directory is a
self-contained sbt project:

```
Blinky/  Button/  ButtonDebouncer/  Pwm/  PwmFade/  Uart/  I2c/
```

Every project owns its own `Makefile`, `build.sbt`,
`icebreaker.pcf`, `src/{hw,sim}/` tree, `README.md`, and
`TODO.md`.

## Cross-project dependencies

Cross-project sbt dependencies are permitted **only** for shared
debug/IO IPs that are themselves stable, hardware-validated tops.
Today that list is:

- **`Uart`** — `UartController` is the canonical debug-output IP.
  Downstream projects depend on it via
  `ProjectRef(file("../Uart"), "uart")` and instantiate
  `uart.UartController` directly rather than copying
  `BaudGenerator` / `UartTx`.

For any other cross-project reuse — and for *all* working IP code
(`Blinky`, `Pwm`, …) — copy the minimum needed locally rather
than introducing an import path. This keeps each example
self-contained unless a sibling is acting as a deliberate, frozen
library.

The consumer's `<Project>/AGENTS.md` documents the dep direction
under a `## Cross-project deps` section, and the consumer's
`Makefile` extends `HW_SRCS` to include the producer's
`src/hw/*.scala` so upstream edits invalidate the generated
Verilog.

## Source layout (per project)

- `src/hw/` — synthesizable code. The Verilog-generation Makefile
  rule depends only on this tree, so anything here triggers a
  rebuild and anything outside doesn't.
- `src/sim/` — SpinalSim testbenches and sim-only helpers
  (Components, Scala harnesses). **Never imported by `src/hw/`.**
- `Makefile` — toolchain pipeline (`sbt → yosys → nextpnr-ice40 →
  icepack → iceprog`) plus per-block `sim-<name>` targets
  aggregated under `sim`.
- `<Project>/TODO.md` — source of truth for in-flight status, with
  a `## ✅ Done` checklist near the top and a `### ✅ Step N` /
  `### 🔲 Step N` block per build step.
- `<Project>/README.md` — user-facing overview; status line stays
  in sync with TODO.md.

## File conventions

- **LF line endings only.** Run `dos2unix` on every file modified
  on a Windows host. Applies to `.scala`, `.md`, `Makefile`,
  `.pcf`, anything checked in.
- Public Makefile targets carry `## description` for `make help`.
- Scala formatting is governed by the repo's `.scalafmt.conf`;
  let it format.

## Comment / Scaladoc style

- **Why, not what.** The code shows the *what*; comments add the
  rationale a reader can't recover from the code alone (electrical
  reasons, protocol corners, choice between alternatives).
- Match the depth of:
  - `Uart/src/hw/UartConfig.scala` — for `case class` configs and
    enums.
  - `Uart/src/hw/BaudGenerator.scala` — for top-level
    Components, including the design-rationale block at the top.
- Doc-link breadcrumbs in source headers (Spinal stdlib pages,
  datasheet sections) are welcome — they don't go stale and they
  save the next reader a search.

## Bottom-up workflow per block

Every new block follows this sequence end-to-end:

1. Write the synthesizable Component in `<Project>/src/hw/`.
2. Write a SpinalSim companion `<Block>Sim.scala` in
   `<Project>/src/sim/`. Pure `case class` configs (e.g.
   `UartConfig`, `I2cConfig`) skip this step — there is no RTL to
   drive.
3. Add a `sim-<block>` target to `<Project>/Makefile`, wire it
   into the `sim` aggregate and `.PHONY` list.
4. Add a `### ✅ Step N — <block>` "What landed" entry to
   `<Project>/TODO.md`. If the original `### 🔲` hint block
   diverged from what shipped, document the divergence and *why*.
5. Update `<Project>/README.md` if the visible status changed.

Skip none of these — partial completions create silent
inconsistencies between code, build, sim, and docs.

## Commit conventions

- Short imperative subject lines.
- AI-pair-programmed commits include the trailer:
  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```

## What NOT to do

- Don't add lint, build, or test infrastructure that doesn't
  already exist. The current toolchain (sbt, yosys, nextpnr,
  iceprog, gtkwave) is intentional.
- Don't run sims or builds that the user didn't ask for; running
  the existing `sim-<block>` after a change is fine.
- Don't create planning `.md` files inside the repo. Use the
  per-session workspace (`~/.copilot/session-state/<id>/`) for
  ephemeral plans.
- Don't introduce cross-project sbt dependencies *except* for
  shared debug/IO IPs whitelisted in the "Cross-project
  dependencies" section above (today: `Uart`). For everything
  else, copy the minimum needed code locally and note its origin
  in a header comment.
- Don't drive any open-drain bus high (see `I2c/AGENTS.md`).
