import { computed, createApp, nextTick, ref } from 'vue'
import MarkdownRender from 'markstream-vue'
import { useStickToBottom } from 'markstream-vue/utils'
import 'markstream-vue/index.css'
import 'katex/dist/katex.min.css'
import { escapeCurrencyDollars } from './currency-guard'
import { AST_CASES, SAMPLES, type Sample } from './dev-samples'

//
// Bridge between the JavaFX side and the Vue app.
// The Java view (MarkstreamView) drives this page through
// window.executeScript calls to the functions exposed on window below.
//
// Sticky-bottom auto-scroll uses the library's useStickToBottom composable:
// the page follows new content while "pinned" to the bottom, stops following
// when the user scrolls up (wheel, touch, keyboard, or scrollbar), and
// resumes when the user scrolls back near the bottom.
//

declare global {
  interface Window {
    msAppend: (chunk: string) => void
    msComplete: () => void
    msReset: () => void
    msLoad: (text: string) => void
    msSetTheme: (theme: string) => void
  }
}

const app = createApp({
  components: { MarkdownRender },
  setup() {
    const content = ref('')
    // :final - the parser treats the document as complete (history loaded or
    // stream finished) instead of leaving trailing constructs in a loading state.
    const finalDoc = ref(false)
    // :smooth-streaming - paces visible output while a stream is in flight;
    // disabled for already-complete content, which should render immediately.
    const smoothStreaming = ref<boolean | 'auto'>('auto')
    let streaming = false

    // What the renderer actually sees: `content` stays the model's own words
    // (that string is what the Java side mirrors into saved .md files), while
    // this view hides the dollars that would otherwise be read as TeX delimiters.
    //
    // `?raw=1` (dev only) switches the guard off, so the same text can be shown
    // both ways and compared. It exists because a clean render proves nothing
    // about the renderer once the guard is in front of it.
    const rawMode = import.meta.env.DEV
      && typeof window !== 'undefined'
      && new URLSearchParams(window.location.search).has('raw')
    if (rawMode) {
      console.warn('[markstream] raw mode: currency guard is OFF')
    }
    const displayContent = computed(() =>
      rawMode ? content.value : escapeCurrencyDollars(content.value))

    // --- code block surface knobs -------------------------------------------
    // Nested-scroll fix: a <pre> captures the mouse wheel only while its own
    // scroll port can move. The library default (500px max-height) turns every
    // long block into a nested scroller that eats the wheel until it hits its
    // own end; CodeBlockOptions.maxHeight must be a positive finite number, so
    // "no limit" means a value no real block will ever reach. The same option
    // is honored by both code surfaces (enhanced and pre).

    // Line numbers are the expensive part of the pre surface: with numbers and
    // wrap the renderer gives every logical line its own <span> so the gutter
    // stays aligned across wrapped rows (PreCodeNode.wrapsPlainCodeLines).
    // Without numbers the block collapses to a single text node, so a 100-line
    // fence costs 1 element instead of 100. Dropping the gutter also moves code
    // about 5ch to the left, which is what a transcript wants anyway.
    const codeBlockOptions = { maxHeight: 8192, disableLineNumbers: true }
    // Toggle knob: adds .hide-code-headers to the wrapper div; the CSS rule in
    // markstream-view.html hides the shell header (icon + title + copy bar).
    // Headers stay ON by default.
    const showCodeHeaders = ref(true)

    // #app is the scroll container (see markstream-view.html): element
    // 'scroll' events are reliable here, unlike document-viewport scroll
    // events in this WebView.
    const scrollRoot = ref<HTMLElement | null>(document.getElementById('app'))
    const contentRoot = ref<HTMLElement | null>(null)
    // threshold: how close to the bottom (px) still counts as "pinned".
    // Doubles as the tolerance for re-sticking when scrolling back down,
    // and the distance you must scroll up past to break away.
    const stick = useStickToBottom(scrollRoot, contentRoot, { threshold: 10 })

    function enterStreaming() {
      if (streaming) {
        return
      }
      streaming = true
      finalDoc.value = false
      smoothStreaming.value = true
    }

    function exitStreaming() {
      if (!streaming) {
        return
      }
      streaming = false
      finalDoc.value = true
      smoothStreaming.value = false
    }

    window.msSetTheme = (theme: string) => {
      document.documentElement.classList.toggle('dark', theme === 'dark')
    }

    window.msAppend = (chunk: string) => {
      if (chunk.length === 0) {
        return
      }
      enterStreaming()
      content.value += chunk
      // Wait for this render pass, then follow; later paced frames are picked
      // up by the composable's ResizeObserver on the content root.
      nextTick(() => stick.scheduleScrollToBottom())
    }

    window.msComplete = () => {
      exitStreaming()
      nextTick(() => stick.scheduleScrollToBottom())
    }

    window.msReset = () => {
      streaming = false
      content.value = ''
      finalDoc.value = false
      smoothStreaming.value = 'auto'
      nextTick(() => {
        // A fresh stream starts pinned: follow from the top.
        stick.bottomPinned.value = true
        stick.scrollToBottom()
      })
    }

    window.msLoad = (text: string) => {
      streaming = false
      content.value = text
      // "Recovering history" mode: the content is already complete, so no
      // pacing (it would artificially slow content the user wants to see
      // immediately) and a final parse.
      finalDoc.value = true
      smoothStreaming.value = false
      nextTick(() => {
        // Land at the newest content.
        stick.bottomPinned.value = true
        stick.scrollToBottom()
      })
    }

    // --- dev-only hand-testing kit (dropped from the production build) --------

    // Vite folds import.meta.env.DEV to false for a build, so the panel markup
    // below is dropped and the timer can never fire in the WebView. The sample
    // strings themselves do stay in the bundle (a few hundred bytes, inert).
    const samples = SAMPLES
    const draft = ref(SAMPLES[1].text)
    let streamTimer: ReturnType<typeof window.setInterval> | undefined

    function loadSample(s: Sample) {
      draft.value = s.text
      window.msLoad(s.text)
    }

    function loadDraft() {
      window.msLoad(draft.value)
    }

    function clearAll() {
      window.msReset()
    }

    /** Push the draft out in small pieces, like a model answering. */
    function streamDraft() {
      window.msReset()
      const text = draft.value
      let i = 0
      if (streamTimer !== undefined) {
        window.clearInterval(streamTimer)
      }
      streamTimer = window.setInterval(() => {
        window.msAppend(text.slice(i, i + 12))
        i += 12
        if (i >= text.length) {
          window.msComplete()
          window.clearInterval(streamTimer)
        }
      }, 25)
    }

    /**
     * Sends the report cases straight to the parser and prints what it produced.
     * Rendering proves only that text looks wrong; the node tree is what shows a
     * math_inline node wrapping prose, which is the thing worth filing.
     */
    async function dumpAst() {
      const mod = await import('markstream-vue') as unknown as {
        getMarkdown?: (id?: string) => unknown
        parseMarkdownToStructure?: (md: string, inst: unknown, opts?: unknown) => unknown
      }
      if (typeof mod.getMarkdown !== 'function' || typeof mod.parseMarkdownToStructure !== 'function') {
        console.error('[markstream] markstream-vue does not re-export getMarkdown/parseMarkdownToStructure.'
          + ' Add the parser package and import it here instead.')
        return
      }
      const inst = mod.getMarkdown('currency-report')
      for (const c of AST_CASES) {
        const nodes = mod.parseMarkdownToStructure(c.text, inst, { final: true })
        const json = JSON.stringify(nodes)
        const hits = json.match(/"math_inline"/g)?.length ?? 0
        console.log(`=== ${c.name} ===\ninput: ${c.text}\nmath_inline count: ${hits}\nAST: ${json}\n`)
      }
    }

    return {
      content,
      displayContent,
      finalDoc,
      smoothStreaming,
      contentRoot,
      codeBlockOptions,
      showCodeHeaders,
      samples,
      rawMode,
      draft,
      loadSample,
      loadDraft,
      clearAll,
      streamDraft,
      dumpAst,
    }
  },
  template:
    '<div ref="contentRoot" :class="{ \'hide-code-headers\': !showCodeHeaders }">' +
    // render-code-blocks-as-pre: plain <pre><code> output (PreCodeNode) instead of the
    // enhanced code surface — lighter, no syntax highlighting or copy/diff controls.
    '  <MarkdownRender mode="chat" :content="displayContent" :final="finalDoc" :smooth-streaming="smoothStreaming" :code-block-options="codeBlockOptions" render-code-blocks-as-pre />' +
    (import.meta.env.DEV
      ? '<div style="position:fixed;left:0;right:0;bottom:0;z-index:9;border-top:1px solid #b0a98f;' +
        'background:#fffdf5;padding:6px 10px;box-shadow:0 -2px 8px rgb(0 0 0 / 15%);font:12px system-ui">' +
        '<div v-if="rawMode" style="color:#a00;font-weight:600">raw mode: currency guard is OFF</div>' +
        '<div style="display:flex;flex-wrap:wrap;gap:6px;align-items:center">' +
        '<button v-for="s in samples" :key="s.name" :title="s.note" @click="loadSample(s)">{{ s.name }}</button>' +
        '<span style="flex:1"></span>' +
        '<button @click="streamDraft">stream draft</button>' +
        '<button @click="loadDraft">load draft</button>' +
        '<button @click="clearAll">clear</button>' +
        '<button title="show/hide the code block header bar" @click="showCodeHeaders = !showCodeHeaders">code header: {{ showCodeHeaders ? "on" : "off" }}</button>' +
        '<button title="Print the parsed node tree for the four report cases to the browser console" @click="dumpAst">dump AST</button>' +
        '</div>' +
        '<textarea v-model="draft" rows="5" style="width:100%;margin-top:6px;font:12px ui-monospace,monospace"></textarea>' +
        '</div>'
      : '') +
    '</div>',
})

app.mount('#app')
