package dev.turnfab;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface Bot extends ChatMemoryAccess {
	TokenStream chat(@UserMessage String message, OpenAiChatRequestParameters parameters);
}
