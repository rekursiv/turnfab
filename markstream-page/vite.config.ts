import { fileURLToPath, URL } from 'node:url'
import type { Plugin, ViteDevServer } from 'vite'
import Vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// Vite hoists module scripts into <head> and keeps type="module" in the output.
// ES modules execute after parsing and are fetched through the module loader,
// which is exactly what we do not want inside the JavaFX WebView: the load
// worker's SUCCEEDED can fire before the deferred module ran (so the window
// bridge was still undefined), and module loading from jar:/classpath URLs is
// finicky. The bundle is an IIFE, so it works fine as a classic script: rewrite
// the tag and move it to the end of <body>, where it executes synchronously
// during parsing - before any load event, and after #app exists.
const classicScriptPlugin = {
  name: 'mdview-classic-script',
  apply: 'build',
  transformIndexHtml: {
    order: 'post',
    handler(html) {
      const match = html.match(/<script type="module" crossorigin src="[^"]+"><\/script>/)
      if (!match) return html
      const tag = match[0].replace(' type="module" crossorigin', '')
      return html.replace(match[0], '').replace('</body>', `${tag}\n  </body>`)
    },
  },
}

//
// Builds the markstream-vue page that mdview-fx loads inside its WebView.
// The output is written directly into the Java resources
// (src/main/resources/markstream), so the built page is packaged inside the
// mdview-fx jar. Commit the generated files after building.
//
/**
 * The page is `markstream-view.html`, not `index.html`, and only the build
 * config declares that (via rollupOptions.input). In dev, Vite looks for
 * `<root>/index.html` when you ask for `/`, finds none, and answers 404 - which
 * looks like a dead server even though Vite is running fine. Redirect the bare
 * root to the real page so `http://localhost:5173/` behaves like any other Vite
 * app instead of surprising you every time.
 */
const devRootRedirect = (): Plugin => ({
  name: 'mdview-dev-root-redirect',
  apply: 'serve',
  configureServer(server: ViteDevServer) {
    server.middlewares.use((req, res, next) => {
      if (req.url === '/' || req.url === '') {
        res.writeHead(302, { Location: '/markstream-view.html' })
        res.end()
        return
      }
      next()
    })
  },
})

export default defineConfig(({ command }) => ({
  // Relative base so the built page works when loaded from classpath/jar URLs.
  base: command === 'build' ? './' : '/',
  plugins: [Vue(), classicScriptPlugin, devRootRedirect()],
  server: {
    // Go straight to the page that exists, instead of Vite's fallback 404.
    open: '/markstream-view.html',
  },
  resolve: {
    alias: {
      // main.ts uses an in-DOM template, so use the full Vue build
      // (with the template compiler) instead of the runtime-only default.
      vue: 'vue/dist/vue.esm-bundler.js',
    },
  },
  build: {
    target: 'es2023',
    outDir: fileURLToPath(new URL('../src/main/resources/dev/turnfab/markstream', import.meta.url)),
    // outDir lives outside this project's root; opt in explicitly.
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      input: fileURLToPath(new URL('./markstream-view.html', import.meta.url)),
      output: {
        // A single self-contained IIFE script (no module loading, no dynamic chunks):
        // dynamic import() of separate chunk files is not reliable when the page is
        // loaded from classpath/jar URLs inside the JavaFX WebView.
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name][extname]',
      },
    },
  },
}))
