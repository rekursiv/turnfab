package dev.turnfab;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface Bot extends ChatMemoryAccess {
	TokenStream chat(@UserMessage String message, OpenAiChatRequestParameters parameters);

//	TokenStream chat(dev.langchain4j.data.message.UserMessage message, OpenAiChatRequestParameters parameters);
//  dev.langchain4j.service.IllegalConfigurationException: The parameter 'arg0' in the method 'chat' of the class dev.turnfab.Bot must be annotated with either dev.langchain4j.service.UserMessage, dev.langchain4j.service.V, dev.langchain4j.service.MemoryId, or dev.langchain4j.service.UserName, or it should be of type dev.langchain4j.invocation.InvocationParameters or dev.langchain4j.model.chat.request.ChatRequestParameters
}
