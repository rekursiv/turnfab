# AGENTS.md

Guidance for an agent working on Turnfab. Read this once, then verify anything specific against
the files. The rule that keeps this doc true: if a fact can be read from a file, point to the file
and do not restate it. Never put versions, counts, or file inventories here.

## What Turnfab is

A coding harness (JavaFX + Guice) whose thesis is *give the model what it needs up front*. Instead
of asking the model to spend early turns discovering its own context, Turnfab pre-loads source
code and documentation into the user prompt it sends, using "tool context" — running tools itself
and folding the results into the first prompt. The bet is that one source file plus a one-line
prompt is often enough to finish a job in a single turn.

## How the pieces connect

The UI controllers never call the model directly. Everything flows through a Google EventBus:
controllers publish, `TokenJockey` consumes and drives a LangChain4j `AiServices` streaming chat
against an OpenAI-compatible endpoint, and streams rendered markdown back down the bus to the
view. If you are tracing a request, follow the events, not the imports. Config lives in
`../turnfab_config/` (model, MCP servers, tool contexts, setups, system prompts) and saved
sessions are saved/restored through `SessionFile`.

## The two dependencies — and why they behave differently

Turnfab stands on two things, and they are *not* the same kind of dependency. Getting this wrong
is the main way you will hurt the project.

- **LangChain4j** is a normal JVM/Gradle classpath dependency. Its types and behaviour are what
  your code compiles against; read `gradle/libs.versions.toml` for the exact version and consult
  LangChain4j's own docs for API. Its integration lives in `TokenJockey` (memory, streaming,
  token counting) and `ToolManager` / `DynamicMcpToolProvider` (MCP tools).

- **markstream-vue is not on the classpath at all.** It is a separate pnpm + Vite Vue application
  in `markstream-page/`, built by that folder's own toolchain and emitted into the Java resources,
  then loaded into a JavaFX `WebView` (`MarkstreamView.java`). No markdown parsing happens on the
  Java side — the renderer's own incremental parser does that work in the page.
  Consequences worth internalising: upgrading it is `pnpm install && pnpm build` in
  `markstream-page/`, **not** a Gradle change (see its README); the built output is git-ignored, so
  a fresh checkout is broken until you run that build; and what you can change from Java is the
  thin `executeScript` bridge (`append`/`load`/`reset`/link handling), not the rendering itself.
  When a streaming-render bug shows up, decide which side of that bridge it is on before editing.

Everything here that could rot lives behind a pointer on purpose. New, durable *how/why* facts
belong in this file; new versions, lists, and key names belong only in the file that is their
source of truth.
