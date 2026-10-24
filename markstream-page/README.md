# markstream-page

This folder builds the web page that `MarkdownMarkstreamView` loads inside its
JavaFX WebView. It wraps [markstream-vue](https://github.com/Simon-He95/markstream-vue) —
a Vue 3 streaming markdown renderer — in a single self-contained page.

## Why a wrapper?

`markstream-vue` is an ESM npm library with dynamic imports and (optionally) web
workers. The JavaFX WebView loads pages from classpath/jar URLs, where loading
separate dynamic chunks is not reliable. So instead of loading the library at
runtime, we bundle everything (Vue + markstream-vue + its dependencies) into one
IIFE script with inlined dynamic imports, and build a single HTML page around it.

The page exposes a small bridge on `window` that the Java side calls via
`WebEngine.executeScript`:

| function            | purpose                                            |
| ------------------- | -------------------------------------------------- |
| `msAppend(chunk)`   | append a markdown chunk to the rendered content    |
| `msComplete()`      | mark the current stream as final                   |
| `msReset()`         | clear the content, preparing for a new stream      |
| `msLoad(text)`      | load a complete document (history recovery) at once|
| `msSetTheme(name)`  | switch light/dark theme (`"light"` / `"dark"`)     |

## Building

One-time (or whenever you change anything here):

```sh
cd markstream-page
corepack enable        # or: npm install -g pnpm
pnpm install
pnpm build
```

The build writes directly into `spike.webkit/src/main/resources/markstream/`
(replacing the placeholder `markstream-view.html` that is shown until the
first build). The generated files are **git-ignored** (see the root
`.gitignore`): every developer — and anyone building the packaged jar — must
run this build once; until then the WebView shows the placeholder page.

To preview the page in a regular browser during development: `pnpm dev`.

## Notes

- Sticky-bottom auto-scroll uses markstream-vue's `useStickToBottom`
  composable: the page follows new content while pinned to the bottom, stops
  following when the user scrolls up (wheel, touch, keyboard, or scrollbar),
  and resumes when the user scrolls back near the bottom (built-in threshold: 10)
- `msLoad` renders an already-complete document immediately ("recovering
  history" mode: `smooth-streaming=false`, `final=true`) and lands at the
  newest content. A later `msAppend` switches back to streaming mode, so a
  new stream appends after the loaded history.
- Currency vs math: a `$` that reads as a money amount is rewritten to the
  `\$` escape before the renderer parses it (`src/currency-guard.ts`), so one
  reply can contain both `$E = mc^2$` and "two tickets: $312 and $289". Dollars
  inside code fences and code spans are left alone. To see it work without
  running the app: `pnpm dev`, then the sample buttons in the bottom bar
  (`import.meta.env.DEV` folds them out of a production build).
  Note: this page is `markstream-view.html`, not `index.html`. Before the root
  redirect was added, `http://localhost:5173/` answered 404 while Vite was
  running quite happily, because dev only serves `<root>/index.html`. The page
  lives at `http://localhost:5173/markstream-view.html` either way.
- The default markstream theme follows the `dark` class on `<html>`; brand
  themes (e.g. `data-theme="claude"`) can be set by extending `msSetTheme`.
