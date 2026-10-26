package dev.turnfab;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class TokenJockey {

    private static final int MAX_TOOL_CALL_DETAIL_CHUNKS = 20;
    private static final int LONG_TOOL_CALL_RATE_DIV = 20;

    @Inject private Logger log;
    @Inject private EventBus eb;
    @Inject private SystemConfig cfg;
    @Inject private DynamicMcpToolProvider toolProvider;
    @Inject private ToolManager toolManager;

    private QwenMode qwenMode = QwenMode.INSTRUCT;
    private Bot bot;
    private String systemPrompt = "";
    private TokenCountEstimator tcEst = new OpenAiTokenCountEstimator("o200K_BASE"); // hack to trigger this.encoding = ENCODING_REGISTRY.getEncoding(O200K_BASE);

    // The window is what the model sees; JournalingChatMemory also keeps the full session
    // trajectory, including whatever has already evicted.
    private JournalingChatMemory chatMemory;

    private Section currentSection = Section.NONE;
    private int currentToolIndex = -1;
    private int toolCallChunks = 0;
    private int turnNumber = 0;

    // streaming stats
    private long streamStartNanos;
    private int outputTokens;
    private int inputTokens;
    private long lastLabelUpdateNanos;

    private volatile boolean statsRecomputePending = false;
    private boolean armCancel = false;
    private enum Section { NONE, THINKING, RESPONSE, TOOL_CALL }

    private final ConfigManager<ModelConfig> modelCfgMgr = ConfigManager.yaml(ModelConfig.class, "../config/McpServers.yaml");
    private ModelConfig modelCfg = new ModelConfig();




    public void initBot() {
        ensureMemory();
        if (bot == null) buildBot();
    }

    /**
     * The window has to exist before anything reads or writes it, and either {@link #beginTurn} or
     * {@link #loadSession} can be the first thing that runs after startup, so both of them call this.
     */
    private void ensureMemory() {
        if (chatMemory != null) return;

        modelCfg = modelCfgMgr.load();
        chatMemory = new JournalingChatMemory(
                TokenWindowChatMemory.withMaxTokens(calcMaxTokens(), tcEst), () -> turnNumber);
    }

    /** Everything that needs a live model, built on the memory that {@link #ensureMemory()} guarantees. */
    private void buildBot() {
        if (chatMemory == null) ensureMemory();

        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName(modelCfg.name)
                .baseUrl(modelCfg.url)
                .apiKey(modelCfg.key)
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(java.net.http.HttpClient.newBuilder()
                                .version(java.net.http.HttpClient.Version.HTTP_1_1)))
                .logRequests(cfg.logRequests)
                .logResponses(cfg.logResponses)
                .returnThinking(true)
                .sendThinking(true, "reasoning")
                .build();


        bot = AiServices.builder(Bot.class)
                .streamingChatModel(new ThinkingFirstStreamingModel(model))
                .hallucinatedToolNameStrategy(req -> {
                    log.warning("Non-existent tool called: '" + req.name() + "'");
                    return ToolExecutionResultMessage.from(req, "Tool '" + req.name() + "' does not exist.");
                })
                .systemMessageTransformer(_ -> systemPrompt)
                .toolProvider(toolProvider)
                .chatMemory(chatMemory)
                .build();

        log.info("Model and AI Service Ready.");
    }

    public void armCancel() {
        armCancel = true;
    }

    @Subscribe
    public void beginTurn(BeginTurnEvent evt) {

        ++turnNumber;
        if (turnNumber==1) {  // system prompt stays the same, ensuring prefix cache hits
            if (evt.getSystemPrompt()==null || evt.getSystemPrompt().isBlank()) systemPrompt = "blank"; // text cannot be null or blank
            else systemPrompt = evt.getSystemPrompt();
            sendStream("### ==System Prompt:==\n");
            sendStream(systemPrompt);
        }

        if (bot==null) {
            buildBot();
        }

        armCancel = false;
        currentSection = Section.NONE;
        currentToolIndex = -1;

        sendStream("\n\n## ==Turn "+turnNumber+"==\n");
        if (evt.getUserPromptSummary()!=null && !evt.getUserPromptSummary().isBlank()) sendStream(evt.getUserPromptSummary());
        else sendStream(evt.getUserPrompt());

        // The thinking toggle is picked up every turn from the shared SystemConfig singleton,
        // so flipping it in the Setup window takes effect on the next turn without any rebuild.
        qwenMode = cfg.enableThinking ? QwenMode.THINKING : QwenMode.INSTRUCT;

        // reset streaming stats
        inputTokens = tcEst.estimateTokenCountInMessages(chatMemory.messages());
        outputTokens = 0;
        streamStartNanos = System.nanoTime();
        lastLabelUpdateNanos = 0;
        statsRecomputePending = false;

        // TextContent + @UserMessage: sent verbatim as a single content part, no {{...}} template
        // processing (see Bot#chat). The per-turn parameters ride along as request parameters and
        // override anything the model builder's defaults specify.
        TokenStream stream = bot.chat(TextContent.from(evt.getUserPrompt()), qwenMode.parameters());

        stream
                .onPartialThinkingWithContext((PartialThinking partialThinking, PartialThinkingContext context) -> {
                    if (partialThinking.text() == null || partialThinking.text().isEmpty()) return;
                    recomputeStatsIfPending();
                    if (currentSection != Section.THINKING) {
                        closeSection();
                        sendStream("\n\n==Thinking:==\n");
                        currentSection = Section.THINKING;
                    }
                    sendStream(partialThinking.text());
                    outputTokens += tcEst.estimateTokenCountInText(partialThinking.text());
                    sendStats();
                    if (armCancel) {
                        context.streamingHandle().cancel();
                        sendStream("\n\n==CANCELLED==\n\n");
                        flushStream(true);
                    }
                })
                .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
                    if (partialResponse.text() == null || partialResponse.text().isEmpty()) return;
                    recomputeStatsIfPending();
                    if (currentSection == Section.TOOL_CALL) return; // don't steal section mid tool-call streaming
                    if (currentSection != Section.RESPONSE) {
                        closeSection();
                        sendStream("\n\n==Response:==\n");
                        currentSection = Section.RESPONSE;
                    }
                    sendStream(partialResponse.text());
                    outputTokens += tcEst.estimateTokenCountInText(partialResponse.text());
                    sendStats();
                    if (armCancel) {
                        context.streamingHandle().cancel();
                        sendStream("\n\n==CANCELLED==\n\n");
                        flushStream(true);
                    }
                })
                .onPartialToolCall(partialToolCall -> {
                    recomputeStatsIfPending();
                    if (currentSection != Section.TOOL_CALL
                            || partialToolCall.index() != currentToolIndex) {
                        if (currentSection == Section.TOOL_CALL) {
                            sendStream("`"); // close the previous call's fenced block
                        }
                        closeSection();
                        sendStream("\n\n==Tool Call:==   *"+partialToolCall.id()+" : "+partialToolCall.name()+"*   `");
                        currentSection = Section.TOOL_CALL;
                        currentToolIndex = partialToolCall.index();
                        toolCallChunks = 0;
                    }
                    if (toolCallChunks < MAX_TOOL_CALL_DETAIL_CHUNKS) {
                        sendStream(partialToolCall.partialArguments());
                    } else if (toolCallChunks%LONG_TOOL_CALL_RATE_DIV==0) {
                        sendStream(".");
                    }
                    toolCallChunks++;
                })
                .onIntermediateResponse(chatResponse -> {
                    // The model finished streaming this tool-calling round: every partial
                    // callback for it has already been delivered. Reset so the next round
                    // opens fresh sections instead of continuing stale ones.
                    if (currentSection == Section.TOOL_CALL) {
                        sendStream("`"); // close the last call's fenced block
                    }
                    closeSection();
                    currentSection = Section.NONE;
                    currentToolIndex = -1;
                    // Stats recompute is deferred: tool results only land in chat memory after
                    // every onToolExecuted has fired. recomputeStatsIfPending() picks it up at
                    // the start of the next round.
                })
                .onToolExecuted(execution -> {
                    flushStream(false);
                    sendStream("> Tool Result:   "+execution.request().id()+" : "+execution.request().name());
                    if (execution.duration().toSeconds()>1)	sendStream("    took "+execution.duration().toSeconds()+" seconds*  \n");
                    else sendStream("\n");
                    if (execution.hasFailed()) sendStream("`"+execution.result()+"`\n");
                    flushStream(false);
                    statsRecomputePending = true;
                })
                .onCompleteResponse(response -> {
                    currentSection = Section.NONE;
                    currentToolIndex = -1;
//					log.info("'"+response.aiMessage().text()+"'");
//					log.info("finishReason="+response.metadata().finishReason()+",  "+response.metadata().tokenUsage());
                    sendStream("\n\n- Turn complete. Tokens In: "+response.metadata().tokenUsage().inputTokenCount()+
                            "   Tokens Out: "+response.metadata().tokenUsage().outputTokenCount()+
                            "   Total: "+response.metadata().tokenUsage().totalTokenCount()+"\n");
                    flushStream(true);
                })
                .onError(error -> {
                    currentSection = Section.NONE;
                    currentToolIndex = -1;
                    error.printStackTrace();
                    flushStream(true);
                })
                .start();
    }

    /**
     * Recomputes streaming stats at the first streaming callback of a new round, once the
     * previous round's tool results are actually in chat memory. No-op unless a tool round
     * happened:
     *      When true, the stats recompute is deferred until the first streaming callback of the
     *      next round: tool results are only persisted to chat memory after every onToolExecuted
     *      has fired, so reading the memory any earlier would miss them.
     */
    private void recomputeStatsIfPending() {
        if (!statsRecomputePending) return;
        statsRecomputePending = false;
        inputTokens = tcEst.estimateTokenCountInMessages(chatMemory.messages());
        outputTokens = 0;
        streamStartNanos = System.nanoTime();
        lastLabelUpdateNanos = 0;
    }

    /**
     * Closes the currently open section, if any: finalizes the in-progress markdown
     * so the next section's header doesn't get absorbed into it.
     */
    private void closeSection() {
        if (currentSection != Section.NONE) {
            flushStream(false);
            sendStream("\n");
        }
    }

    private void flushStream(boolean endOfTurn) {
        runOnJavaFx(()->eb.post(new TokenFlushEvent(endOfTurn)));
    }

    private void sendStream(String md) {
        runOnJavaFx(()->eb.post(new TokenStreamEvent(md)));
    }

    /**
     * Updates the token/s and context-usage labels, throttled to at most every 200 ms.
     * Called from streaming callbacks (non-FX thread).
     */
    private void sendStats() {
        long now = System.nanoTime();
        if (now - lastLabelUpdateNanos < 200_000_000L) return; // 200 ms throttle
        lastLabelUpdateNanos = now;

        double elapsedSec = (now - streamStartNanos) / 1_000_000_000.0;
        double tokensPerSec = elapsedSec > 0.2 ? outputTokens / elapsedSec : 0;
        int totalTokens = inputTokens + outputTokens;
        double percentTokensUsed = 100.0 * totalTokens / modelCfg.length;

        runOnJavaFx(()->eb.post(new TokenStatsEvent(modelCfg.length, totalTokens, (float)percentTokensUsed, (float)tokensPerSec)));

    }

    private int calcMaxTokens() {
        return (int) (modelCfg.length*(modelCfg.trim_context_percent/100.0f));
    }

    private void runOnJavaFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Everything that has to come back for this session to continue where it stopped: the window, the
     * turn counter, the system prompt. The markdown half of a session file is the caller's, since it is
     * the view layer that has been collecting it.
     */
    public SessionFile.Session currentSession(String markdown) {
        ensureMemory();
        return new SessionFile.Session(turnNumber, modelCfg.name, null, systemPrompt, chatMemory.messages(), markdown,
                cfg.enableThinking, toolManager.currentSetup());
    }

    /**
     * Makes the saved session the current one: window and journal replaced outright rather than merged,
     * because "continue where I left off" means the file <em>is</em> the session, then the turn counter,
     * system prompt, thinking flag and tool setup put back the way the file had them.
     * <p>
     * Loading is meant to happen between turns: a turn already streaming would write over the restored
     * memory as its tool rounds land.
     */
    public void loadSession(SessionFile.Session snap) {
        ensureMemory();

        List<ChatMessage> restored = trimDanglingToolRequests(snap.messages());
        int dropped = snap.messages().size() - restored.size();
        if (dropped > 0) {
            log.warning("Dropped " + dropped + " unfinished tool-call message(s) from the loaded session's tail.");
        }

        chatMemory.reset();
        // Turn number moves first, so the journal tags the restored entries with the turn they loaded on.
        turnNumber = snap.turnNumber();
        chatMemory.set(restored);

        int inWindow = chatMemory.messages().size();
        if (inWindow < restored.size()) {
            log.warning("Window holds " + calcMaxTokens() + " tokens: evicted "
                    + (restored.size() - inWindow) + " of the loaded message(s) on the way in.");
        }

        if (snap.systemPrompt() != null && !snap.systemPrompt().isBlank()) {
            systemPrompt = snap.systemPrompt();
        } else {
            log.warning("Session file carries no system prompt; continuing with: '" + systemPrompt + "'");
        }

        if (snap.enableThinking() != null) {
            cfg.enableThinking = snap.enableThinking();
            log.info("Thinking restored: " + (cfg.enableThinking ? "on" : "off"));
        }
        restoreTools(snap.tools());

        log.info("Session loaded: " + inWindow + " message(s) in window, next turn " + (turnNumber + 1));
    }

    /**
     * A tool setup doesn't deserialize, it reconnects, so this is the part of a load that can fail on
     * its own: a server that is down now is not a reason to drop a session whose conversation is intact.
     * Whatever did connect is what the next turn gets.
     */
    private void restoreTools(SessionFile.ToolSetup tools) {
        if (tools == null) return;
        try {
            List<String> live = toolManager.applySetup(tools);
            log.info("Tools restored (" + live.size() + " server(s)): " + String.join(", ", live));
        } catch (Exception e) {
            log.log(Level.WARNING, "Tool setup not restored, continuing with whatever is connected", e);
        }
    }

    /**
     * A window ending on a tool request is a half-finished exchange: providers that forbid an
     * {@code AiMessage} with no following {@code ToolExecutionResultMessage} reject it, and there is no
     * honest way to continue from it. Dropping the tail is the only interpretation that is always safe.
     */
    private static List<ChatMessage> trimDanglingToolRequests(List<ChatMessage> messages) {
        List<ChatMessage> trimmed = new ArrayList<>(messages);
        while (!trimmed.isEmpty()
                && trimmed.get(trimmed.size() - 1) instanceof AiMessage ai
                && ai.hasToolExecutionRequests()) {
            trimmed.remove(trimmed.size() - 1);
        }
        return trimmed;
    }

    public void testJournal() {
        List<JournalingChatMemory.Entry> journal = chatMemory.journal();
        for (JournalingChatMemory.Entry entry : journal) {
            System.out.println(entry.toString());
        }
    }

}
