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
@SuppressWarnings("removal")
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
          // Nothing is skipped because of defaultPrevented: in the capture phase it reads false
          // here in any case, and a link some other script has already cancelled is still a link.
          var target = null;
          var path = (typeof e.composedPath === 'function') ? e.composedPath() : [];
          for (var i = 0; i < path.length; i++) {
            if (path[i] && path[i].nodeType === 1 && path[i].tagName === 'A') { target = path[i]; break; }
          }
          if (!target && e.target && e.target.closest) { target = e.target.closest('a'); }
          if (!target) { return; }
          var href = target.getAttribute('href');
          if (href === null) { return; }
          if (!window.msLinks) {
            // Nobody to hand the click to. Better to let it through: the location guard will
            // announce it and put the page back, which beats a link that seems to work and does
            // nothing. If this branch is ever taken, the bridge went away under a working hook.
            return;
          }
          // From here the click is ours: no navigation, whatever the anchor claims. What to do
          // with it is decided in Java, which has the log. This side's only job is to report that
          // a link was clicked and what it points at, even when the answer is "nothing", because a
          // quietly dropped link and a missing hook look exactly the same from the outside.
          e.preventDefault();
          e.stopPropagation();
          var url = null;
          try { url = new URL(href, document.baseURI).href; } catch (bad) { url = null; }
          if (url === null) { window.msLinks.blocked(href); } else { window.msLinks.open(url); }
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

  /**
   * Both halves of the callback bridge, held for as long as the view lives: the object the page
   * calls, and the wrapper that was handed the registration.
   *
   * <p>Neither can be a temporary. WebKit keeps its end of a {@code setMember} registration alive
   * through these references, and the wrapper that {@code executeScript("window")} returns is
   * otherwise only ever a local - so an unreferenced bridge can be collected by a garbage
   * collection in the middle of a session, leaving the page holding a property that points at
   * nothing. Every click after that is answered with silence, the page itself carries on working,
   * and only a restart clears it.
   */
  private final LinkBridge linkBridge = new LinkBridge();
  private netscape.javascript.JSObject pageWindow;

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
   * page lock - which is why it is still the choice here. The name is written out in full rather
   * than imported so the removal allowance can be given to the class rather than to the file.
   *
   * <p>The wrapper this returns is kept in {@link #pageWindow} instead of being used and dropped,
   * for the reason given on {@link #linkBridge}.
   */
  private void wireLinks() {
    try {
      pageWindow = null;
      pageWindow = (netscape.javascript.JSObject) engine.executeScript("window");
      pageWindow.setMember("msLinks", linkBridge);
    } catch (RuntimeException e) {
      log.log(Level.WARNING, "Link bridge not installed into the page", e);
    }
    // The bridge before the hook, and only when the page confirms it. A hook without a bridge
    // swallows clicks that nobody was ever going to open, which is the one way this can fail with
    // no trace at all. Without the hook the click does what a click normally does, and the
    // location guard says so in the log and puts the page back.
    if (!"object".equals(String.valueOf(engine.executeScript("typeof window.msLinks")))) {
      log.warning("No link bridge in the page, so no click hook either: links will navigate and be blocked by the guard");
      return;
    }
    engine.executeScript(LINK_HOOK_JS);
    log.info("Link wiring: bridge=object hook="
        + engine.executeScript("typeof window.__msLinkHook"));
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
    if (isOurPage(url)) {
      // The page's own address, with a fragment on it or not. A plain '#somewhere' is a scroll,
      // and there is no browser that has anything to show for the rest. Logged, because a click
      // that ends here has to be told apart from a click that ended in nowhere.
      log.info("Staying inside the page for " + url);
      return;
    }
    String scheme = schemeOf(url);
    if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
        || scheme.equals("file")) {
      openExternally(url);
      return;
    }
    log.warning("Ignored a link with nothing that could open it: " + url);
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
   * Hands the URL to the operating system: the desktop first, a helper process second.
   *
   * <p>The desktop gets first offer because it is what the platform actually recommends, and it
   * answers with an exception when it cannot, which is a better report than launching something
   * may or may not have opened. The helper is there for the sessions where there is no desktop to
   * ask, which is also the case on a machine with no AWT desktop at all.
   *
   * <p>Both are asked off the FX thread. The browse call documents that it may block for as long
   * as the browser takes to answer, and the thread that draws the UI is not where that belongs.
   */
  private void openExternally(String url) {
    Thread opener = new Thread(() -> {
      if (!browseWithDesktop(url)) {
        browseWithHelper(url);
      }
    }, "open-link");
    opener.setDaemon(true);
    opener.start();
  }

  /**
   * @return true when the desktop was able to take the URL, so there is nothing left to ask.
   */
  private boolean browseWithDesktop(String url) {
    try {
      if (!Desktop.isDesktopSupported()
          || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        return false;
      }
      Desktop.getDesktop().browse(URI.create(url));
      log.info("Opened " + url + " in the system browser");
      return true;
    } catch (IOException
        | IllegalArgumentException
        | UnsupportedOperationException e) {
      // Not a failure yet, since the helper gets the same URL. Fine rather than warning, because
      // the one line this whole path costs on an ordinary click is the one in the helper.
      log.log(Level.FINE, "The desktop has no answer for " + url, e);
      return false;
    }
  }

  /** The fallback for a session with no desktop to ask, whatever opener the environment ships. */
  private void browseWithHelper(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    // Rundll32 on Windows rather than 'cmd /c start': cmd reparses the command line it is given,
    // and an & inside a URL is a command separator to it.
    ProcessBuilder builder = os.contains("win")
        ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
        : os.contains("mac") ? new ProcessBuilder("open", url) : new ProcessBuilder("xdg-open", url);
    try {
      builder.start();
      log.info("Asked " + builder.command().getFirst() + " to open " + url);
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
      // Nothing is allowed to leave this method as an exception. WebKit reports it to the page as
      // a script error, and a script error is a good way to explain a page that goes on not
      // answering clicks, so the failure has to be a log line rather than a broken script context.
      try {
        if (url == null || url.isEmpty()) {
          return;
        }
        // Fine rather than info: what a click is worth on the ordinary path is the one line the
        // opener logs when the URL actually leaves for the browser. This is here for the days the
        // click reaches Java and nothing after it does, and for those days the level gets raised.
        log.fine("Link clicked: " + url);
        openLink(url);
      } catch (Throwable refused) {
        log.log(Level.WARNING, "Could not take the link to " + url, refused);
      }
    }

    /**
     * A click on an anchor whose {@code href} the page could not make sense of, so there is
     * nothing to hand to a browser. All that is left is to say so: the point of this method is
     * that no click is ever answered with silence.
     *
     * @param href the raw attribute value that would not resolve
     */
    public void blocked(String href) {
      log.warning("Clicked link that resolved to nothing: [" + href + "]");
    }
  }

}
