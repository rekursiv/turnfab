package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigBase {
	public boolean cfgUseDefaults;
	public String cfgSaveToFile;
	public String cfgLoadFromFile;
}

