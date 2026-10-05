package dev.turnfab;

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import dev.langchain4j.data.message.TextContent;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class TokenJockey {

    private static final int MAX_TOOL_CALL_DETAIL_CHUNKS = 20;
    private static final int LONG_TOOL_CALL_RATE_DIV = 20;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");

    @Inject private Logger log;
    @Inject private EventBus eb;
    @Inject private SystemConfig cfg;
    @Inject private DynamicMcpToolProvider toolProvider;

    private QwenMode qwenMode = QwenMode.INSTRUCT;
    private Bot bot;
    private StringBuilder systemPrompt = new StringBuilder();
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
        if (cfg.enableThinking) qwenMode = QwenMode.THINKING;
        modelCfg = modelCfgMgr.load();

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
                .defaultRequestParameters(qwenMode.parameters())
                .build();


        bot = AiServices.builder(Bot.class)
                .streamingChatModel(new ThinkingFirstStreamingModel(model))
 //               .systemMessageTransformer(systemMessage -> buildSystemPrompt())
                .systemMessageTransformer(_ -> systemPrompt.toString())
                .toolProvider(toolProvider)
                .chatMemory(chatMemory = new JournalingChatMemory(
                        TokenWindowChatMemory.withMaxTokens(calcMaxTokens(), tcEst), () -> turnNumber))
                .build();

        log.info("Model and AI Service Ready.");
    }

    public void armCancel() {
        armCancel = true;
    }

    public void beginTurn(String toSend) {
        if (bot==null) {
            log.warning("Bot has not been initialized!");
            return;
        }

        ++turnNumber;
        if (turnNumber==1) {
            if (systemPrompt.isEmpty()) {
                try {
                    loadSystemPromptFromFile(cfg.systemPromptFileName);
                } catch (IOException e) {
                    log.log(Level.WARNING, "Could not load system prompt file!", e);
                    return;
                }
            }
            sendStream("### ==System Prompt:==\n");
            sendStream(systemPrompt.toString());
        }

        armCancel = false;
        currentSection = Section.NONE;
        currentToolIndex = -1;

        sendStream("\n\n## ==Turn "+turnNumber+"==\n");
        if (toSend.length()>900) sendStream("..."+toSend.substring(toSend.length()-900).replace("```", ""));
        else sendStream(toSend);

        // reset streaming stats
        inputTokens = tcEst.estimateTokenCountInMessages(chatMemory.messages());
        outputTokens = 0;
        streamStartNanos = System.nanoTime();
        lastLabelUpdateNanos = 0;
        statsRecomputePending = false;

        // TextContent without @UserMessage: sent verbatim as a single content part,
        // no {{...}} template processing (see Bot#chat); reasoning_effort is a model default now.
        TokenStream stream = bot.chat(TextContent.from(toSend));

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
                            sendStream("\n```\n"); // close the previous call's fenced block
                        }
                        closeSection();
                        sendStream("\n\n==Tool Call:==   *"+partialToolCall.id()+" : "+partialToolCall.name()+"*\n```json\n");
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
                        sendStream("\n```\n"); // close the last call's fenced block
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
                    sendStream("\n==Tool Result:==   *"+execution.request().id()+" : "+execution.request().name());
                    if (execution.duration().toSeconds()>1)	sendStream("    took "+execution.duration().toSeconds()+" seconds*  \n");
                    else sendStream("*\n");
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

    public void loadSystemPromptFromFile(String fileName) throws IOException {
        systemPrompt = new StringBuilder();
        String pathStr = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/"+fileName;
		System.out.println("load: "+pathStr);
        for (String line : Files.readAllLines(Paths.get(pathStr), StandardCharsets.UTF_8)) {
            systemPrompt.append(line);
        }
        systemPrompt.append("\nToday's date is " + LocalDate.now().format(DATE_FORMATTER) + ".");
    }

    public void testJournal() {
        List<JournalingChatMemory.Entry> journal = chatMemory.journal();
        for (JournalingChatMemory.Entry entry : journal) {
            System.out.println(entry.toString());
        }
    }

}
