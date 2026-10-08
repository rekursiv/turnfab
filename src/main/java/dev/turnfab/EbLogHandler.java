package dev.turnfab;

import com.google.common.eventbus.EventBus;

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
		logMsg.append(record.getSequenceNumber()+": ");
		logMsg.append(record.getMessage()+"  [");
		logMsg.append(record.getLevel()+"]  {");
		logMsg.append(record.getSourceClassName()+"#");
		logMsg.append(record.getSourceMethodName()+"}  ");
		if (record.getThrown()!=null) {
			logMsg.append(record.getThrown());
		}
		logMsg.append("\n");
		eb.post(new LogEvent(logMsg.toString()));
	}
	
	@Override
	public void flush() {	
	}
	
	@Override
	public void close() throws SecurityException {
	}

}
