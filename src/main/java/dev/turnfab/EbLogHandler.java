package dev.turnfab;

import com.google.common.eventbus.EventBus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class EbLogHandler extends Handler {

    private EventBus eb;

    public EbLogHandler(EventBus eb) {
        this.eb = eb;
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;
        StringBuilder logMsg = new StringBuilder();
        logMsg.append(record.getSequenceNumber() + ": ");
        logMsg.append(record.getMessage() + "  [");
        logMsg.append(record.getLevel() + "]  {");
        logMsg.append(record.getSourceClassName() + "#");
        logMsg.append(record.getSourceMethodName() + "}  ");
        if (record.getThrown() != null) {
            logMsg.append(trace(record.getThrown()));
        }
        logMsg.append("\n");
        eb.post(new LogEvent(logMsg.toString()));
    }

    /**
     * The whole stack trace of a logged Throwable, cause chains included.
     * Used to be just record.getThrown(), which is one line: the class name and message,
     * which is all the Log tab ever showed for a logged exception.
     */
    public static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw, true));
        return sw.toString();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }

}
