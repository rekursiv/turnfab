package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpJetBrainsConfig extends ConfigBase {

	public List<Project> projects = new ArrayList<>();

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Project {
		public String name = "";
		public int port = 0;
		public String projectPath = "";
		public boolean readOnly = true;
	}
}
