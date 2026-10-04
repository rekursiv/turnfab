package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelConfig extends ConfigBase {

	public String name = "qwen3.8-flash-next-a5b";
	public String url = "http://venus.local:8000/v1";
	public String key = "secret";
	public int length = 262144;
	public int trim_context_percent = 70;

}
