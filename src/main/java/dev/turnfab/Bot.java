package dev.turnfab;

import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface Bot extends ChatMemoryAccess {

	// TextContent (not a @UserMessage String): a @UserMessage String is treated as a template,
	// so any {{...}} in the text would be substituted or rejected. Passing TextContent *without*
	// @UserMessage sends it verbatim as a single content part. See the caveat about @UserMessage
	// TextContent: LC4J duplicates it (DefaultAiServices.addContentsToUserMessage).
	TokenStream chat(TextContent message);
}
