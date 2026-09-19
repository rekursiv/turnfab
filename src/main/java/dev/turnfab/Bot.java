package dev.turnfab;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * A declarative AI service. We only declare the API we want; LangChain4j's
 * {@code AiServices} creates a proxy that implements it, hiding all of the
 * low-level {@code ChatMessage} / {@code ChatRequest} plumbing.
 */
public interface Bot {

	/**
	 * Streams a reply to the user's message.
	 *
	 * <p>The {@link TokenStream} return type tells AiServices to deliver the
	 * model's response token-by-token via callbacks instead of blocking until
	 * the whole reply is ready.
	 *
	 * <p>The {@code message} parameter is annotated with {@link UserMessage},
	 * which is required because the method has a second, non-message parameter:
	 * the model parameters for this call. Any provider-specific parameters
	 * (e.g. reasoning effort) passed here are merged into the LLM request.
	 */
	@SystemMessage("You are a friendly, conversational chatbot.")
	TokenStream chat(@UserMessage String message, OpenAiChatRequestParameters parameters);
}
