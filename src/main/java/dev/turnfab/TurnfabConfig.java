package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnfabConfig extends ConfigBase {

	// logging
	public boolean logToConsole=true;
	public boolean logToFile=false;

	public String model_name = "qwen3.8-flash-next-a5b";
	public String model_base_url = "http://venus.local:8000/v1";
	public String model_key = "secret";
	public int model_length = 262144;
	public int trim_context_percent = 70;

}
