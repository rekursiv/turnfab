package dev.turnfab;


import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ConfigManager<T> {

	protected String defaultFileName;
	protected ObjectMapper mapper=null;
	protected Stack<Exception>  errorStack;
	protected Class<T> modelType;
	
	public ConfigManager(Class<T> modelType, String defaultFileName) {
		this.modelType = modelType;
		this.defaultFileName = defaultFileName;
		errorStack= new Stack<Exception>();
	}
	
	public boolean hasErrors() {
		return !errorStack.isEmpty();
	}
	
	public ConfigBase createConfig() {
		try {
			return (ConfigBase) modelType.newInstance();
		} catch (Exception e) {
			errorStack.push(e);
		} 
		return null;
	}
	
	public T load() {
		if (mapper==null) mapper = new ObjectMapper();
		ConfigBase config = createConfig();
		
		if (!config.cfgUseDefaults) {
		
			if (Files.exists(buildPath(defaultFileName))) {
				try {
					config = (ConfigBase) loadFromFile(defaultFileName);
				} catch (Exception e) {
					errorStack.push(e);
				}
				if (config==null || config.cfgUseDefaults) {
					config = createConfig();
				}
			} else {
				config.cfgSaveToFile = defaultFileName;
			}
			
			if (config.cfgLoadFromFile!=null) {
				try {
					config = (ConfigBase) loadFromFile(config.cfgLoadFromFile);
				} catch (Exception e) {
					errorStack.push(e);
				}
			} 
			
			if (config.cfgSaveToFile!=null) {
				String fileName = config.cfgSaveToFile;
				if (fileName.isEmpty()) {  // use empty string to update current cfg file with new vars added to config class
					fileName = defaultFileName;
				}
				config.cfgSaveToFile = null;
				try {
					saveToFile(fileName, config);
				} catch (Exception e) {
					errorStack.push(e);
				}
			}
			
		}
		
//		debug(config);
		
		return modelType.cast(config);
	}

	public void logErrorsIfAny() {
		Logger log = Logger.getGlobal();
		while (!errorStack.empty()) log.log(Level.SEVERE, "CONFIG", errorStack.pop());
	}
	
	public void save(Object model) throws Exception {
//		if (!Files.exists(buildPath(defaultFileName))) {
			saveToFile(defaultFileName, model);
//		}
	}
	
	public void saveToFile(String fileName, Object model) throws Exception {
//		System.out.println("save: "+fileName);
		List<String> lines = Arrays.asList(getText(model).split("\\n"));
		Files.write(buildPath(fileName), lines, StandardCharsets.UTF_8);
	}
	
	public String getText(Object model) throws Exception {
		if (mapper==null) mapper = new ObjectMapper();
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model).replace("\r", "");
	}
	
	public void update(String text, Object model) throws Exception {
		mapper.readerForUpdating(model).readValue(text);
	}
	
	public T mapConfigFromText(String text) throws Exception {
		return mapper.readValue(text, modelType);
	}
	
	public Path buildPath(String fileName) {
		String pathStr = System.getProperty("user.dir")+"/"+fileName;
//		System.out.println(pathStr);
		Path path = Paths.get(pathStr);
		return path;
	}

	public T loadFromFile(String fileName) throws Exception {
//		System.out.println("load: "+fileName);
		StringBuilder sb = new StringBuilder();
		for (String line : Files.readAllLines(buildPath(fileName), StandardCharsets.UTF_8)) {
			if (sb.length()>0) sb.append("\n");
			sb.append(line);
		}
		return mapper.readValue(sb.toString(), modelType);
	}

	
	
	@SuppressWarnings("unused")
	private void debug(Object obj) {
		if (obj==null) {
			System.out.println("!!!   NULL");
			return;
		}
		System.out.println("--------------");
		try {
			if (mapper==null) mapper = new ObjectMapper();
			System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("^^^^^^^^^^^^^^");
	}


	
}
