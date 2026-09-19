import { createApp, nextTick, ref } from 'vue'
import MarkdownRender from 'markstream-vue'
import { useStickToBottom } from 'markstream-vue/utils'
import 'markstream-vue/index.css'

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
      smoothStreaming.value = 'auto'
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

    return { content, finalDoc, smoothStreaming, contentRoot }
  },
  template:
    '<div ref="contentRoot">' +
    '  <MarkdownRender mode="chat" :content="content" :final="finalDoc" :smooth-streaming="smoothStreaming" />' +
    '</div>',
})

app.mount('#app')
