package dev.turnfab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import javafx.concurrent.Worker.State;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
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
 */
public class MarkstreamView {

  /** Classpath location of the built markstream page. */
  private static final String PAGE_RESOURCE = "markstream/markstream-view.html";

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
    engine.load(pageUrl);
    engine
        .getLoadWorker()
        .stateProperty()
        .addListener(
            (obs, oldState, newState) -> {
              if (newState == State.SUCCEEDED) {
                ready = true;
//                applyTheme();
              }
            });
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

}
