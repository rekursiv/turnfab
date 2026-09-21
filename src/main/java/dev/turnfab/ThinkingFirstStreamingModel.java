package dev.turnfab;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow.Publisher;

/**
 * A {@link StreamingChatModel} decorator that fixes a LangChain4j callback-ordering quirk
 * with reasoning models.
 * <p>
 * Some providers (OpenAI o-series, DeepSeek, vLLM, many OpenAI-compatible gateways) emit a
 * single streaming chunk whose delta contains both the <em>tail</em> of the thinking text
 * ({@code reasoning_content}) and the <em>first</em> answer token ({@code content}) at the
 * thinking-to-answer boundary. LangChain4j's
 * {@code ChatCompletionEventDispatcher} processes the fields of such a chunk in a fixed
 * order - content first, then reasoning - so the application receives
 * {@code onPartialResponse} <em>before</em> the last {@code onPartialThinking}, even though
 * the model generated the thinking first. UIs that render thinking and response as
 * alternating sections end up with an out-of-order transcript:
 * <pre>
 *   ==Thinking:==  ...Keep
 *   ==Response:==  Hey
 *   ==Thinking:==   it natural and brief.
 *   ==Response:==  ! ...
 * </pre>
 * <p>
 * This wrapper restores the logical order (all thinking before any response) by holding
 * back each answer chunk for an instant and, when a thinking chunk arrives <em>after</em>
 * the first answer chunk (the only moment the two can be out of order with reasoning
 * models), forwarding the thinking chunk first and then the held answer chunks.
 * <p>
 * Costs and limits:
 * <ul>
 *     <li>Only kicks in once thinking has been seen in the current round, so responses
 *         without thinking stream with zero added latency.</li>
 *     <li>In a thinking round each answer chunk is delayed by one chunk (typically tens of
 *         milliseconds) while it waits for the next event; the stream stays live.</li>
 *     <li>Only the handler-based (callback) streaming path is re-ordered. The reactive
 *         {@link #doChat(ChatRequest)} publisher is passed through unchanged.</li>
 *     <li>Only one streaming round can be in flight per handler instance; state is local
 *         to each {@code doChat} call and all callbacks arrive on a single thread, so no
 *         synchronization is needed.</li>
 * </ul>
 */
public class ThinkingFirstStreamingModel implements StreamingChatModel {

	private final StreamingChatModel delegate;

	public ThinkingFirstStreamingModel(StreamingChatModel delegate) {
		this.delegate = delegate;
	}

	@Override
	public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
		delegate.doChat(chatRequest, new ReorderingHandler(handler));
	}

	/**
	 * The reactive path is passed through untouched; re-ordering is only applied to the
	 * handler-based path used by AI Services' {@code TokenStream}.
	 */
	@Override
	public Publisher<ChatModelStreamingEvent> doChat(ChatRequest chatRequest) {
		return delegate.doChat(chatRequest);
	}

	@Override
	public ChatRequestParameters defaultRequestParameters() {
		return delegate.defaultRequestParameters();
	}

	@Override
	public List<ChatModelListener> listeners() {
		return delegate.listeners();
	}

	@Override
	public ModelProvider provider() {
		return delegate.provider();
	}

	@Override
	public Set<Capability> supportedCapabilities() {
		return delegate.supportedCapabilities();
	}

	/**
	 * One instance per streaming round. Re-orders partial thinking/response callbacks so
	 * that thinking is always delivered before the response, no matter how the provider
	 * batches its chunks.
	 */
	private final class ReorderingHandler implements StreamingChatResponseHandler {

		private record HeldResponse(PartialResponse response, PartialResponseContext context) {}

		private final StreamingChatResponseHandler downstream;
		private final Deque<HeldResponse> heldResponses = new ArrayDeque<>();
		private boolean thinkingSeen;

		ReorderingHandler(StreamingChatResponseHandler downstream) {
			this.downstream = downstream;
		}

		@Override
		public void onPartialResponse(String partialResponse) {
			if (partialResponse == null || partialResponse.isEmpty()) {
				downstream.onPartialResponse(partialResponse);
				return;
			}
			hold(new PartialResponse(partialResponse), null);
		}

		@Override
		public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
			hold(partialResponse, context);
		}

		private void hold(PartialResponse partialResponse, PartialResponseContext context) {
			if (!thinkingSeen) {
				// Round starts with an answer: almost certainly a non-thinking response.
				// Forward immediately so plain responses keep zero added latency.
				if (context != null) {
					downstream.onPartialResponse(partialResponse, context);
				} else {
					downstream.onPartialResponse(partialResponse.text());
				}
				return;
			}
			// Thinking was seen in this round: a thinking chunk may still follow this
			// answer chunk (mixed boundary chunk), so hold it until we know better.
			flushHeld();
			heldResponses.addLast(new HeldResponse(partialResponse, context));
		}

		@Override
		public void onPartialThinking(PartialThinking partialThinking) {
			thinkingSeen = true;
			downstream.onPartialThinking(partialThinking);
			flushHeld();
		}

		@Override
		public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
			thinkingSeen = true;
			downstream.onPartialThinking(partialThinking, context);
			flushHeld();
		}

		private void flushHeld() {
			HeldResponse held;
			while ((held = heldResponses.pollFirst()) != null) {
				if (held.context() != null) {
					downstream.onPartialResponse(held.response(), held.context());
				} else {
					downstream.onPartialResponse(held.response().text());
				}
			}
		}

		@Override
		public void onPartialToolCall(PartialToolCall partialToolCall) {
			flushHeld();
			downstream.onPartialToolCall(partialToolCall);
		}

		@Override
		public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
			flushHeld();
			downstream.onPartialToolCall(partialToolCall, context);
		}

		@Override
		public void onCompleteToolCall(CompleteToolCall completeToolCall) {
			flushHeld();
			downstream.onCompleteToolCall(completeToolCall);
		}

		@Override
		public void onUnmappedRawEvent(Object rawEvent) {
			flushHeld();
			downstream.onUnmappedRawEvent(rawEvent);
		}

		@Override
		public void onCompleteResponse(ChatResponse completeResponse) {
			flushHeld();
			downstream.onCompleteResponse(completeResponse);
		}

		@Override
		public void onError(Throwable error) {
			// The stream is dying: do not release held chunks after the error, they would
			// land after the error handling in the application.
			downstream.onError(error);
		}
	}
}
