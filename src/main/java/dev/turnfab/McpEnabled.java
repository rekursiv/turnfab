package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpEnabled extends ConfigBase {

	public Set<String> enabled = new TreeSet<>();

}
