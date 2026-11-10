# Turnfab

**An agentic coding harness that hands the model what it needs before it has to ask.**

Turnfab drives a large language model from a desktop window rather than a terminal, over MCP, so the
model is pointed at your real IDE — the same tools you already use. Most harnesses spend their first
turns deciding what context a task needs and fetching it. Turnfab skips that: it runs its own tools
ahead of time and folds the results into the very first prompt. That's **tool context** — pre-loaded
source code and documentation, presented as demonstrated tool calls. The bet is that one source file
plus a one-line prompt is often enough to finish a job in a single turn.

## How it's organized

A JavaFX + Guice desktop app. The UI controllers don't call the model directly — everything flows
through an internal event bus, with `TokenJockey` bridging to a streaming LLM and a built-in web view
that renders the markdown as it arrives. The two things it stands on (LangChain4j, and the
markstream-vue renderer) are loaded in different ways, and knowing which is which matters before you
edit either — the full map is in **[AGENTS.md](AGENTS.md)**.

Setup is data, not code: models, MCP servers, and named tool contexts live as YAML files next to the
project, not in the source. Point an agent (or yourself) at that folder to see the current state of a
machine without trusting anything written here.

## Building and running

Requires a JDK and Gradle (no global install): `./gradlew run` launches the app via the standard
Gradle application setup; see `build.gradle.kts`.

**One step is easy to miss on a fresh checkout.** The markdown renderer is a separate web page that
is *not* fetched during the Java build — it must be built once from its own folder first, and the
output is git-ignored. If the app starts but shows a placeholder where the transcript should be, that
build hasn't run. Exact commands are in `markstream-page/README.md`.

## For agents / new contributors

If you're an AI coding agent or a newcomer landing cold, read **[AGENTS.md](AGENTS.md)** first. It is
intentionally short and pointer-based: it points at the files that are each fact's source of truth
rather than restating versions, tool lists, or config keys that would go stale. Keep it that way.
