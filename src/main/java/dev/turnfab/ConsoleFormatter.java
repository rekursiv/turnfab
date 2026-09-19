package dev.turnfab;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class ConsoleFormatter extends Formatter {

	 private static final DateFormat df = new SimpleDateFormat("MM/dd|hh:mm:ss.SSS");
	
	@Override
	public String format(LogRecord record) {
        StringBuilder builder = new StringBuilder(1000);
        builder.append("["+df.format(new Date(record.getMillis()))+"]");
        builder.append(" \""+formatMessage(record)+"\"");
        builder.append(" {"+record.getLevel()+":");
        builder.append(record.getSourceClassName()+"#");
        builder.append(record.getSourceMethodName()+"}  ");
        builder.append("\n");
		if (record.getThrown()!=null) {
			builder.append(record.getThrown());
	        builder.append("\n");
		}
		return builder.toString();
	}

}
