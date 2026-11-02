package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
//@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class SystemConfig extends ConfigBase {
	public boolean logToConsole=true;
	public boolean logToFile=false;


}
