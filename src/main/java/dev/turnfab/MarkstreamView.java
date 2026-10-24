package dev.turnfab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import javafx.application.Platform;
import javafx.concurrent.Worker.State;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * WebView-based view that renders streaming markdown with the <a
 * href="https://github.com/Simon-He95/markstream-vue">markstream-vue</a> renderer.
 *
 * <p>No markdown parsing happens on the Java side. This view feeds raw markdown
 * text into a self-contained web page that runs markstream-vue inside the WebView,
 * letting the library's own incremental parser do the work.
 *
 * <p>The page is loaded from {@code dev/turnfab/markstream/markstream-view.html} on
 * the classpath. It is produced by building the {@code markstream-page} folder (see
 * its README: {@code pnpm install} and {@code pnpm build}), which writes the built
 * page into the module resources. Until that is done, a placeholder page explaining
 * the steps is shown instead.
 *
 * <p>All methods must be called from the JavaFX application thread. This class no longer does any
 * thread hopping of its own, and {@link WebEngine#executeScript} is only safe on the FX thread.
 *
 * <p>Links are the one thing a view like this must not be allowed to do on its own: WebKit would
 * happily load whatever the model writes into a link and there is no way back out. Two layers keep
 * the view on its page - a hook in the page that hands clicked URLs over to {@link LinkBridge},
 * which opens them in the system browser, and a location guard that catches whatever the hook
 * cannot see and puts the page back if it was ever replaced.
 */
public class MarkstreamView {

  /** Classpath location of the built markstream page. */
  private static final String PAGE_RESOURCE = "markstream/markstream-view.html";

  /**
   * Runs in the page once it is loaded. A capture-phase click listener takes every anchor click
   * before the renderer's own handlers, resolves the {@code href} the way the browser would, and
   * hands it to {@code window.msLinks} - the Java side of {@link LinkBridge} - instead of letting
   * the click turn into a navigation. It sits on {@code document} rather than on anchors because
   * the streaming renderer replaces its DOM under us constantly, and it looks through
   * {@code composedPath} so links inside a shadow tree are caught too.
   */
  private static final String LINK_HOOK_JS =
      """
      (function () {
        if (window.__msLinkHook) { return; }
        window.__msLinkHook = true;
        // Nothing gets to open a window that nobody would show.
        window.open = function () { return null; };
        function onClickLink(e) {
          if (e.defaultPrevented) { return; }
          var target = null;
          var path = (typeof e.composedPath === 'function') ? e.composedPath() : [];
          for (var i = 0; i < path.length; i++) {
            if (path[i] && path[i].nodeType === 1 && path[i].tagName === 'A') { target = path[i]; break; }
          }
          if (!target && e.target && e.target.closest) { target = e.target.closest('a'); }
          if (!target) { return; }
          var href = target.getAttribute('href');
          if (!href) { return; }
          var url = null;
          try { url = new URL(href, document.baseURI).href; } catch (bad) { url = null; }
          // The click belongs to us now: no navigation, whatever the anchor claims.
          e.preventDefault();
          e.stopPropagation();
          if (!url) { return; }
          // A plain '#somewhere' is just a scroll inside the page, nothing to open.
          if (url.split('#')[0] === location.href.split('#')[0]) { return; }
          if (window.msLinks) { window.msLinks.open(url); }
          else { console.warn('[markstream] no link bridge, blocked ' + url); }
        }
        // Both, because a middle click arrives as 'auxclick' and would otherwise get through.
        document.addEventListener('click', onClickLink, true);
        document.addEventListener('auxclick', onClickLink, true);
      })();
      """;

  /** Shown when the built page is missing from the classpath. */
  private static final String PAGE_MISSING_HTML =
      "<!doctype html><html><head><meta charset='utf-8'>"
          + "<style>body{font-family:system-ui,sans-serif;display:grid;place-content:center;"
          + "height:100vh;margin:0;color:#444}code{background:#eee;padding:2px 6px;"
          + "border-radius:4px}pre{background:#eee;padding:10px;border-radius:6px;"
          + "overflow:auto}</style></head><body><div style='text-align:center;max-width:36rem'>"
          + "<h1>Markstream page not built yet</h1>"
          + "<p>This placeholder is replaced by the real page after you build it:</p>"
          + "<pre>cd markstream-page\npnpm install\npnpm build</pre>"
          + "<p>Output is written to"
          + " <code>src/main/resources/dev/turnfab/markstream</code> and is"
          + " git-ignored, so run the build in every fresh checkout.</p>"
          + "</div></body></html>";

  @Inject private Logger log;

  private final WebView webView = new WebView();
  private final WebEngine engine = webView.getEngine();
  private final BorderPane borderPane = new BorderPane();
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private volatile boolean ready = false;

  /** The page's own URL, and the folder it sits in: everything below that is still our page. */
  private String pageUrl;
  private String pageBase;
  /** Everything this view has handed the page. Kept for one thing: a reloaded page gets its
   * transcript back instead of coming up empty. */
  private StringBuilder served = new StringBuilder();
  /** Set while a hijacked page is being reloaded, cleared once the content is served again. */
  private boolean restoring = false;

  public MarkstreamView() {
    borderPane.setCenter(webView);
    webView.setContextMenuEnabled(false);
    URL url = getClass().getResource(PAGE_RESOURCE);
    if (url == null) {
      log.warning(
          "Markstream page not found at " + PAGE_RESOURCE
              + " - build it in the markstream-page folder (pnpm install && pnpm build)."
              + " Showing a placeholder.");
      engine.loadContent(PAGE_MISSING_HTML);
      return;
    }
    String pageUrl;
    try {
      pageUrl = materializeOnDisk(url);
    } catch (IOException | URISyntaxException e) {
      log.log(Level.WARNING, "Failed to stage the markstream page from " + url, e);
      engine.loadContent(PAGE_MISSING_HTML);
      return;
    }
    this.pageUrl = pageUrl;
    pageBase = pageUrl.substring(0, pageUrl.lastIndexOf('/') + 1);
    engine.load(pageUrl);
    engine
        .getLoadWorker()
        .stateProperty()
        .addListener(
            (obs, oldState, newState) -> {
              // Any load that is not finished is a page we cannot talk to.
              ready = newState == State.SUCCEEDED;
              if (newState != State.SUCCEEDED) {
                return;
              }
//                applyTheme();
              // A reload gets a brand new window object, so the wiring is redone on every load.
              wireLinks();
              if (restoring) {
                restoring = false;
                String transcript = served.toString();
                if (!transcript.isEmpty()) {
                  engine.executeScript("msLoad(" + jsStringLiteral(transcript) + ");");
                }
              }
            });
    guardLocation();
  }

  /**
   * Returns a URL the WebEngine can actually load.
   *
   * <p>In a development run the resources sit in the classes folder, so {@code getResource}
   * yields a {@code file:} URL and the page's relative asset references ({@code ./assets/...})
   * resolve as siblings without help. Inside a jar the same call returns a {@code jar:} URL:
   * WebKit can load that main document, but its network layer does not resolve relative
   * sub-resources against {@code jar:} URLs (see JDK-8159447), so the script and stylesheet are
   * never fetched and the view renders broken. When running from a jar, extract the page folder
   * into the temp directory first and load it from there - shipping stays one single jar.
   */
  private String materializeOnDisk(URL url) throws IOException, URISyntaxException {
    if (!"jar".equals(url.toURI().getScheme())) {
      return url.toExternalForm(); // classes folder during development: fine as is
    }
    String prefix = getClass().getPackageName().replace('.', '/') + "/markstream/";
    Path pageDir = Path.of(System.getProperty("java.io.tmpdir"), "dev.turnfab", "markstream");
    JarURLConnection connection = (JarURLConnection) url.openConnection();
    connection.setUseCaches(false);
    try (JarFile jar = connection.getJarFile()) {
      for (JarEntry entry : jar.stream().filter(e -> !e.isDirectory()).toList()) {
        if (!entry.getName().startsWith(prefix)) {
          continue;
        }
        Path target = pageDir.resolve(entry.getName().substring(prefix.length()));
        Files.createDirectories(target.getParent());
        try (InputStream in = jar.getInputStream(entry)) {
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    // Keep repeated runs from leaving stale copies behind.
    try (Stream<Path> paths = Files.walk(pageDir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().deleteOnExit());
    }
    return pageDir.resolve("markstream-view.html").toUri().toURL().toExternalForm();
  }

  /**
   * Gives the page the two halves of its link handling: the object it calls, and the hook that
   * calls it. Both need a loaded document, so this runs again after every reload.
   *
   * <p>{@code netscape.javascript.JSObject} is how a WebView lets a page call back into the JVM,
   * and it is deprecated for removal since JDK 24. It still works, and the page keeps its click
   * hook even if this one call fails, so what would be lost is the external open rather than the
   * page lock - which is why it is still the choice here. The name is written out in full so the
   * suppression can sit on this method instead of on the file.
   */
  @SuppressWarnings("removal")
  private void wireLinks() {
    try {
      ((netscape.javascript.JSObject) engine.executeScript("window"))
          .setMember("msLinks", new LinkBridge());
    } catch (RuntimeException e) {
      // The hook still swallows the click, so failing here costs the external open and not the
      // page itself: a silent browser rather than a hijacked one.
      log.log(Level.WARNING, "Link bridge not installed, links will be blocked instead of opened", e);
    }
    engine.executeScript(LINK_HOOK_JS);
  }

  /**
   * The second line of defence, for what the click hook cannot catch: a script that writes to
   * {@code location}, a meta refresh, a form. The engine announces every address it means to be
   * at, and anywhere that is not part of the page means putting the page back.
   */
  private void guardLocation() {
    engine.locationProperty().addListener((obs, was, is) -> {
      if (isOurPage(is)) {
        return;
      }
      log.warning("Blocked a navigation away from the markstream page: " + is);
      // The engine is not in a fit state to be spoken to from inside its own listener.
      Platform.runLater(this::restorePage);
    });
  }

  /**
   * Puts the page back - but only when the address really did move. Where WebKit refused the load
   * by itself, the old document never went away and there is nothing to do.
   */
  private void restorePage() {
    if (isOurPage(engine.getLocation())) {
      return;
    }
    restoring = true;
    engine.load(pageUrl);
  }

  /** Whether {@code url} is the page, something inside its folder, or the page with an anchor. */
  private boolean isOurPage(String url) {
    if (url == null || pageUrl == null) {
      return true; // nothing loaded yet, which is ours as much as anything
    }
    return url.startsWith(pageBase) || withoutFragment(url).equals(withoutFragment(pageUrl));
  }

  private static String withoutFragment(String url) {
    int hash = url.indexOf('#');
    return hash < 0 ? url : url.substring(0, hash);
  }

  /**
   * The whole rule for a clicked link, in one line: the system browser, or nothing. Never this
   * view, which is the only way the chat is guaranteed to still be here afterwards.
   */
  private void openLink(String url) {
    String scheme = schemeOf(url);
    if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
        || scheme.equals("file")) {
      if (!isOurPage(url)) {
        openExternally(url);
      }
      return;
    }
    log.warning("Ignored a link with nothing to open it: " + url);
  }

  /** The {@code scheme:} a URL opens with, or the empty string when it has none. */
  private static String schemeOf(String url) {
    int colon = url.indexOf(':');
    if (colon <= 0) {
      return "";
    }
    for (int i = 0; i < colon; i++) {
      char c = url.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
        return "";
      }
    }
    return url.substring(0, colon).toLowerCase(Locale.ROOT);
  }

  /**
   * Hands the URL to the operating system. AWT's browse call is free to block until the browser
   * gets back to it, and the FX thread is not where that should happen.
   */
  private void openExternally(String url) {
    Thread opener = new Thread(() -> {
      try {
        if (Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
          Desktop.getDesktop().browse(URI.create(url));
          return;
        }
      } catch (IOException
          | IllegalArgumentException
          | SecurityException
          | UnsupportedOperationException e) {
        log.log(Level.FINE, "Desktop.browse could not open " + url, e);
      }
      browseWithHelper(url);
    }, "open-link");
    opener.setDaemon(true);
    opener.start();
  }

  /** No desktop session to work with (a minimal Linux install, a headless box): ask the shell. */
  private void browseWithHelper(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    ProcessBuilder builder = os.contains("win")
        ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
        : os.contains("mac") ? new ProcessBuilder("open", url) : new ProcessBuilder("xdg-open", url);
    try {
      builder.start();
    } catch (IOException e) {
      log.log(Level.WARNING, "Nothing could open " + url, e);
    }
  }

  /**
   * Returns the root node of this view to embed into a scene graph.
   *
   * @return the WebView container node
   */
  public Node getView() {
    return borderPane;
  }

  /**
   * Whether the page has finished loading and can accept streaming content.
   *
   * @return true once the page is loaded
   */
  public boolean isReady() {
    return ready;
  }

  /** Clears the rendered content, preparing for a new stream. */
  public void reset() {
    if (!ready) {
      log.warning("Markstream page not loaded yet, cannot reset");
      return;
    }
    engine.executeScript("msReset();");
    served.setLength(0);
  }

  /**
   * Appends a chunk of markdown text to the rendered content. The caller supplies incremental
   * chunks (e.g. as they arrive from an LLM). The page starts a new stream on the first append
   * after {@link #reset()} or {@link #complete()}, so appending after a completed stream
   * continues with new content rather than replacing it.
   *
   * @param chunk markdown text to append
   */
  public void append(String chunk) {
    if (chunk == null || chunk.isEmpty()) {
      return;
    }
    if (!ready) {
      log.warning("Markstream page not loaded yet, content chunk dropped");
      return;
    }
    engine.executeScript("msAppend(" + jsStringLiteral(chunk) + ");");
    served.append(chunk);
  }

  /** Marks the current stream as complete. */
  public void complete() {
    if (!ready) {
      return;
    }
    engine.executeScript("msComplete();");
  }

  /**
   * Loads a complete document (e.g. a saved chat session) all at once. The page renders it in
   * history-recovery mode (no streaming pacing, final parse) and jumps to the newest content.
   * Subsequent {@link #append} calls start a fresh stream that continues after the loaded
   * content; call {@link #reset()} first to replace it instead.
   *
   * @param fullText complete markdown text to load
   */
  public void load(String fullText) {
    if (fullText == null) {
      return;
    }
    if (!ready) {
      log.warning("Markstream page not loaded yet, cannot load content");
      return;
    }
    engine.executeScript("msLoad(" + jsStringLiteral(fullText) + ");");
    served = new StringBuilder(fullText);
  }

  /**
   * Encodes {@code s} as a JSON string literal (quoted, with {@code "}, {@code \} and control
   * characters escaped) so it can be inlined into a JavaScript string argument.
   */
  private static String jsStringLiteral(String s) {
    try {
      return JACKSON.writeValueAsString(s);
    } catch (JsonProcessingException e) {
      // Encoding a plain String cannot realistically fail; guard just in case.
      throw new IllegalStateException("failed to encode string for WebView script", e);
    }
  }

  /**
   * The page's one door out. WebKit reaches {@link #open} by reflection, so this class is public,
   * the method is public, and the package is opened to {@code javafx.web} (module-info already
   * does). Only this one method is exposed, which keeps the view's own driving calls
   * ({@code append}, {@code load}, {@code reset}) out of what JavaScript can reach.
   */
  public class LinkBridge {

    /**
     * Called from the page's click hook with a URL it resolved itself. Arrives on the FX thread.
     *
     * @param url absolute URL of the clicked link
     */
    public void open(String url) {
      if (url != null) {
        openLink(url);
      }
    }
  }

}
