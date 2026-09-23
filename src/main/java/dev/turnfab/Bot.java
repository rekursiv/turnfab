package dev.turnfab;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface Bot {
	TokenStream chat(@UserMessage String message, OpenAiChatRequestParameters parameters);
}
