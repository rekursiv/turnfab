package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolContextConfig extends ConfigBase {

    public Map<String, List<Context>> context = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Context {
        public String text = null;
        public String mcpName = null;
        public String toolName = null;
        public String arg1 = null;
        public String arg2 = null;
    }

}
