package dev.turnfab;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.regex.Pattern;

import com.google.common.eventbus.EventBus;

/**
 * Catch-all for the exceptions the Log tab currently misses.
 * The Log tab is fed by EbLogHandler, so it only sees what an actual Logger writes. Two common
 * paths never reach a Logger at all: (1) an exception that escapes some thread with nothing
 * catching it, which the JVM hands to ThreadGroup.uncaughtException and prints to System.err,
 * and (2) any stack trace written straight to System.err, i.e. printStackTrace() calls.
 * Both of those only end up in the run console / terminal, never in the GUI. This installs two
 * hooks so both paths land on the EventBus: a default uncaught exception handler, and a
 * System.err replacement that forwards every byte it gets to the real System.err while scanning
 * the lines for stack traces. Only trace-looking lines are posted, so ordinary System.err
 * printing is not duplicated into the Log tab.
 * What this cannot catch: an exception that code deliberately swallows or that gets parked on a
 * CompletableFuture nobody looks at. Nothing is printed or logged in that case, so there is
 * nothing to intercept; those need a whenComplete()/exceptionally() at the call site.
 */
public class CatchAll {

    /**
     * "java.net.ConnectException", "com.foo.BarException: boom"
     */
    private static final Pattern EXCEPTION = Pattern.compile("[\\w$.]+(?:Exception|Error|Throwable)(?::.*)?");

    private final EventBus eb;
    private final PrintStream err;    // System.err as it was before we replaced it

    /**
     * Set while posting, so a subscriber that trips cannot loop back into us.
     */
    private static final ThreadLocal<Boolean> posting = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private CatchAll(EventBus eb) {
        this.eb = eb;
        this.err = System.err;
        System.setErr(new PrintStream(new ErrSink(this.err), true));
        Thread.setDefaultUncaughtExceptionHandler(this::uncaught);
    }

    /**
     * Install once at startup. Do it AFTER any ConsoleHandler is constructed: ConsoleHandler
     * binds to whichever System.err exists at that moment, and we do not want its output
     * coming back around through this tee.
     */
    public static void install(EventBus eb) {
        new CatchAll(eb);
    }

    /**
     * Runs for every thread that dies with nobody handling its exception.
     */
    private void uncaught(Thread t, Throwable e) {
        post("Uncaught in thread \"" + t.getName() + "\": " + causes(e) + "  [SEVERE]  {uncaught}");
        // write to the real System.err, not the tee, so this is not seen and logged twice
        err.println("Exception in thread \"" + t.getName() + "\"");
        e.printStackTrace(err);
    }

    /**
     * "java.util.concurrent.CompletionException: x  <-  java.net.ConnectException  <-  ..."
     */
    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        for (int hops = 0; c != null && hops < 8; hops++) {
            if (hops > 0) sb.append("  <-  ");
            sb.append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
            c = (c.getCause() == c) ? null : c.getCause();
        }
        return sb.toString();
    }

    private void post(String msg) {
        if (posting.get()) return;
        try {
            posting.set(Boolean.TRUE);
            eb.post(new LogEvent(msg));
        } finally {
            posting.set(Boolean.FALSE);
        }
    }

    /**
     * The stream that stands in for System.err while it is replaced: forwards all of it, posts traces.
     */
    private class ErrSink extends OutputStream {

        private final PrintStream target;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256);

        private boolean inTrace;
        private boolean headSeen;
        private int frames;

        ErrSink(PrintStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            target.write(buf, off, len);
            for (int i = 0; i < len; i++) {
                if (buf[off + i] == '\n') {
                    line(pending.toString());
                    pending.reset();
                } else {
                    pending.write(buf[off + i]);
                }
            }
        }

        @Override
        public void flush() {
            if (pending.size() > 0) {
                line(pending.toString());
                pending.reset();
            }
            target.flush();
        }

        @Override
        public void close() {
            // deliberately not closing target: that is the real console, not our pipe
        }

        /**
         * One complete line of whatever was written to System.err.
         */
        private void line(String raw) {
            String s = raw.strip();        // also drops the \r of a Windows line ending
            if (s.isEmpty()) return;
            String kind = kind(s);

            if (kind == null) {            // anything that is not trace material ends the block
                if (inTrace) {
                    if (frames > 0) post("(+ " + frames + " stack trace lines, see console)  [SEVERE]  {std_err}");
                    inTrace = false;
                    headSeen = false;
                    frames = 0;
                }
                return;
            }
            if (kind.equals("frame")) {
                if (inTrace) frames++;
                return;
            }
            if (!inTrace) {
                inTrace = true;
                headSeen = false;
                frames = 0;
            }
            if (!headSeen) {
                post("Thrown: " + s + "  [SEVERE]  {std_err}");
                headSeen = true;
            } else {
                post(s + "  [SEVERE]  {std_err}");
            }
        }
    }

    /**
     * "frame" for a stack line, "chain" for a line naming an exception, null for everything else.
     */
    private static String kind(String s) {
        if (s.startsWith("at ") || s.startsWith("...")) return "frame";
        if (s.startsWith("Exception in thread ") || s.startsWith("Caused by:") || s.startsWith("Suppressed:"))
            return "chain";
        if (EXCEPTION.matcher(s).matches()) return "chain";
        return null;
    }

}