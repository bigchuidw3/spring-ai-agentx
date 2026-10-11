/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.agentx.ai.core.chatmodels;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion.Choice;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.ai.deepseek.api.common.DeepSeekConstants;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.reactivestreams.Publisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ChatModel} and {@link StreamingChatModel} implementation for {@literal DeepSeek}
 * backed by {@link DeepSeekApi}.
 *
 * @author Geng Rong
 */
public class DeepSeekV4ChatModel implements ChatModel, StreamingChatModel {

	private static final Logger logger = LoggerFactory.getLogger(DeepSeekV4ChatModel.class);

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build();

	/**
	 * The default options used for the chat completion requests.
	 */
	private final DeepSeekChatOptions defaultOptions;

	/**
	 * The retry template used to retry the DeepSeek API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the DeepSeek API.
	 */
	private final DeepSeekApi deepSeekApi;

	/**
	 * Observation registry used for instrumentation.
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * The tool calling manager used to execute tools.
	 */
	private final ToolCallingManager toolCallingManager;

	/**
	 * The tool execution eligibility predicate used to determine if a tool can be
	 * executed.
	 */
	private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

	/**
	 * Conventions to use for generating observations.
	 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	public DeepSeekV4ChatModel(DeepSeekApi deepSeekApi, DeepSeekChatOptions defaultOptions,
	                           ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
	                           ObservationRegistry observationRegistry) {
		this(deepSeekApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry,
				new DefaultToolExecutionEligibilityPredicate());
	}

	public DeepSeekV4ChatModel(DeepSeekApi deepSeekApi, DeepSeekChatOptions defaultOptions,
	                           ToolCallingManager toolCallingManager, RetryTemplate retryTemplate, ObservationRegistry observationRegistry,
	                           ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
		Assert.notNull(deepSeekApi, "deepSeekApi cannot be null");
		Assert.notNull(defaultOptions, "defaultOptions cannot be null");
		Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
		Assert.notNull(retryTemplate, "retryTemplate cannot be null");
		Assert.notNull(observationRegistry, "observationRegistry cannot be null");
		Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate cannot be null");
		this.deepSeekApi = deepSeekApi;
		this.defaultOptions = defaultOptions;
		this.toolCallingManager = toolCallingManager;
		this.retryTemplate = retryTemplate;
		this.observationRegistry = observationRegistry;
		this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalCall(requestPrompt, null);
	}

	public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

		ChatCompletionRequest request = createRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(DeepSeekConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {

				ResponseEntity<ChatCompletion> completionEntity = this.retryTemplate
					.execute(ctx -> this.deepSeekApi.chatCompletionEntity(request));

				var chatCompletion = completionEntity.getBody();

				if (chatCompletion == null) {
					logger.warn("No chat completion returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Choice> choices = chatCompletion.choices();
				if (choices == null) {
					logger.warn("No choices returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Generation> generations = choices.stream().map(choice -> {
			// @formatter:off
					Map<String, Object> metadata = Map.of(
							"id", chatCompletion.id() != null ? chatCompletion.id() : "",
							"role", choice.message().role() != null ? choice.message().role().name() : "",
							"index", choice.index(),
							"finishReason", choice.finishReason() != null ? choice.finishReason().name() : "");
					// @formatter:on
					return buildGeneration(choice, metadata);
				}).toList();

				// Current usage
				DeepSeekApi.Usage usage = completionEntity.getBody().usage();
				Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
				Usage accumulatedUsage = UsageCalculator.getCumulativeUsage(currentChatResponseUsage,
						previousChatResponse);
				ChatResponse chatResponse = new ChatResponse(generations,
						from(completionEntity.getBody(), accumulatedUsage));

				observationContext.setResponse(chatResponse);

				return chatResponse;

			});

		if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
			var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
			if (toolExecutionResult.returnDirect()) {
				// Return tool execution result directly to the client.
				return ChatResponse.builder()
					.from(response)
					.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
					.build();
			}
			else {
				// Send the tool execution result back to the model.
				return this.internalCall(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
						response);
			}
		}

		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return internalStream(requestPrompt, null);
	}

	public Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			ChatCompletionRequest request = createRequest(prompt, true);

			Flux<DeepSeekApi.ChatCompletionChunk> completionChunks = this.deepSeekApi.chatCompletionStream(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();
			ConcurrentHashMap<String, String> reasoningContentMap = new ConcurrentHashMap<>();

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(DeepSeekConstants.PROVIDER_NAME)
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<ChatResponse> chatResponse = completionChunks.map(this::chunkToChatCompletion)
				.switchMap(chatCompletion -> Mono.just(chatCompletion).map(chatCompletion2 -> {
					try {
						String id = chatCompletion2.id();

						List<Generation> generations = chatCompletion2.choices().stream().map(choice -> {
							if (choice.message().role() != null) {
								roleMap.putIfAbsent(id, choice.message().role().name());
							}
							if (choice.message().reasoningContent() != null) {
								reasoningContentMap.merge(id, choice.message().reasoningContent(), String::concat);
							}

				// @formatter:off
								Map<String, Object> metadata = Map.of(
										"id", chatCompletion2.id(),
										"role", roleMap.getOrDefault(id, ""),
										"finishReason", choice.finishReason() != null ? choice.finishReason().name() : ""
								);
  				// @formatter:on
							return buildGeneration(choice, metadata);
						}).toList();
						DeepSeekApi.Usage usage = chatCompletion2.usage();
						Usage currentUsage = (usage != null) ? getDefaultUsage(usage) : new EmptyUsage();
						Usage cumulativeUsage = UsageCalculator.getCumulativeUsage(currentUsage, previousChatResponse);

						return new ChatResponse(generations, from(chatCompletion2, cumulativeUsage));
					}
					catch (Exception e) {
						logger.error("Error processing chat completion", e);
						return new ChatResponse(List.of());
					}

				}));

			// @formatter:off
			Flux<ChatResponse> flux = chatResponse.flatMap(response -> {
				if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
					final ChatResponse toolCallResponse;
					String responseId = response.getMetadata() != null ? response.getMetadata().getId() : "";
					String accumulatedReasoningContent = reasoningContentMap.getOrDefault(responseId, null);
					if (accumulatedReasoningContent != null && !accumulatedReasoningContent.isEmpty()) {
						List<Generation> newGenerations = response.getResults().stream().map(gen -> {
							if (gen.getOutput() instanceof DeepSeekAssistantMessage deepSeekMsg
									&& (deepSeekMsg.getReasoningContent() == null || deepSeekMsg.getReasoningContent().isEmpty())) {
								DeepSeekAssistantMessage newMsg = new DeepSeekAssistantMessage.Builder()
									.content(deepSeekMsg.getText())
									.reasoningContent(accumulatedReasoningContent)
									.properties(deepSeekMsg.getMetadata() != null ? deepSeekMsg.getMetadata() : Map.of())
									.toolCalls(deepSeekMsg.getToolCalls())
									.build();
								return new Generation(newMsg, gen.getMetadata());
							}
							return gen;
						}).toList();
						toolCallResponse = ChatResponse.builder().from(response).generations(newGenerations).build();
					}
					else {
						toolCallResponse = response;
					}
					// FIXME: bounded elastic needs to be used since tool calling
					//  is currently only synchronous
					return Flux.deferContextual(ctx -> {
						ToolExecutionResult toolExecutionResult;
						try {
							ToolCallReactiveContextHolder.setContext(ctx);
							toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, toolCallResponse);
						}
						finally {
							ToolCallReactiveContextHolder.clearContext();
						}
						if (toolExecutionResult.returnDirect()) {
							// Return tool execution result directly to the client.
							return Flux.just(ChatResponse.builder().from(toolCallResponse)
									.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
									.build());
						}
						else {
							// Send the tool execution result back to the model.
							return this.internalStream(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
									toolCallResponse);
						}
					}).subscribeOn(Schedulers.boundedElastic());
				}
				else {
					return Flux.just(response);
				}
			})
			.doOnError(observation::error)
			.doFinally(s -> observation.stop())
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
			// @formatter:on

			return new MessageAggregator().aggregate(flux, observationContext::setResponse);

		});
	}

	private Generation buildGeneration(Choice choice, Map<String, Object> metadata) {
		List<AssistantMessage.ToolCall> toolCalls = choice.message().toolCalls() == null ? List.of()
				: choice.message()
					.toolCalls()
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(), "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

		String finishReason = (choice.finishReason() != null ? choice.finishReason().name() : "");
		var generationMetadataBuilder = ChatGenerationMetadata.builder().finishReason(finishReason);

		String textContent = choice.message().content();
		String reasoningContent = choice.message().reasoningContent();

		DeepSeekAssistantMessage.Builder builder = new DeepSeekAssistantMessage.Builder();
		DeepSeekAssistantMessage assistantMessage = builder.content(textContent)
			.reasoningContent(reasoningContent)
			.properties(metadata)
			.toolCalls(toolCalls)
			.build();

		return new Generation(assistantMessage, generationMetadataBuilder.build());
	}

	private ChatResponseMetadata from(ChatCompletion result, Usage usage) {
		Assert.notNull(result, "DeepSeek ChatCompletionResult must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(result.id() != null ? result.id() : "")
			.usage(usage)
			.model(result.model() != null ? result.model() : "")
			.keyValue("created", result.created() != null ? result.created() : 0L)
			.keyValue("system-fingerprint", result.systemFingerprint() != null ? result.systemFingerprint() : "");
		return builder.build();
	}

	private ChatResponseMetadata from(ChatResponseMetadata chatResponseMetadata, Usage usage) {
		Assert.notNull(chatResponseMetadata, "DeepSeek ChatResponseMetadata must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(chatResponseMetadata.getId() != null ? chatResponseMetadata.getId() : "")
			.usage(usage)
			.model(chatResponseMetadata.getModel() != null ? chatResponseMetadata.getModel() : "");
		return builder.build();
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private ChatCompletion chunkToChatCompletion(DeepSeekApi.ChatCompletionChunk chunk) {
		List<Choice> choices = chunk.choices()
			.stream()
			.map(chunkChoice -> new Choice(chunkChoice.finishReason(), chunkChoice.index(), chunkChoice.delta(),
					chunkChoice.logprobs()))
			.toList();

		return new ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(), chunk.serviceTier(),
				chunk.systemFingerprint(), chunk.usage());
	}

	private DefaultUsage getDefaultUsage(DeepSeekApi.Usage usage) {
		return new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage);
	}

	Prompt buildRequestPrompt(Prompt prompt) {
		DeepSeekChatOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(toolCallingChatOptions, ToolCallingChatOptions.class,
						DeepSeekChatOptions.class);
			}
			else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
						DeepSeekChatOptions.class);
			}
		}

		DeepSeekChatOptions requestOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions,
				DeepSeekChatOptions.class);

		if (runtimeOptions != null) {
			requestOptions.setInternalToolExecutionEnabled(
					ModelOptionsUtils.mergeOption(runtimeOptions.getInternalToolExecutionEnabled(),
							this.defaultOptions.getInternalToolExecutionEnabled()));
			requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(runtimeOptions.getToolNames(),
					this.defaultOptions.getToolNames()));
			requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(),
					this.defaultOptions.getToolCallbacks()));
			requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.getToolContext(),
					this.defaultOptions.getToolContext()));
		}
		else {
			requestOptions.setInternalToolExecutionEnabled(this.defaultOptions.getInternalToolExecutionEnabled());
			requestOptions.setToolNames(this.defaultOptions.getToolNames());
			requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
			requestOptions.setToolContext(this.defaultOptions.getToolContext());
		}

		ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());

		return new Prompt(prompt.getInstructions(), requestOptions);
	}

	/**
	 * Accessible for testing.
	 */
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
				return List.of(new ChatCompletionMessage(message.getText(),
						ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
						return new ToolCall(toolCall.id(), toolCall.type(), function);
					}).toList();
				}
				Boolean isPrefixAssistantMessage = null;
				if (message instanceof DeepSeekAssistantMessage
						&& Boolean.TRUE.equals(((DeepSeekAssistantMessage) message).getPrefix())) {
					isPrefixAssistantMessage = true;
				}
				// tool call 场景下 text 可能为 null（如 DeepSeek thinking 模式）
				String text = assistantMessage.getText();
				if (text == null) {
					text = "";
				}

				// 🔧 FIX #2: 从 properties 中读取 reasoning_content 并回传给 API
				String reasoningContent = null;
				if (message instanceof DeepSeekAssistantMessage deepSeekMsg && deepSeekMsg.getReasoningContent() != null
						&& !deepSeekMsg.getReasoningContent().isEmpty()) {
					reasoningContent = deepSeekMsg.getReasoningContent();
				}
				if (reasoningContent == null || reasoningContent.isEmpty()) {
					// 兼容两种 key 命名：AgentX 框架用 "reasoningContent"，DeepSeek 原生用 "reasoning_content"
					Object rcProp = assistantMessage.getMetadata().get("reasoning_content");
					if (rcProp == null) {
						rcProp = assistantMessage.getMetadata().get("reasoningContent");
					}
					if (rcProp instanceof String rc && !rc.isEmpty()) {
						reasoningContent = rc;
					}
				}
				// DeepSeek 要求 assistant 消息携带 reasoning_content 字段（空字符串即可），缺失则 400；
				// 模型偶发不吐思考，以及压缩生成的合成 assistant（无思考链）都会缺该字段，统一补空
				if (reasoningContent == null || reasoningContent.isEmpty()) {
					reasoningContent = "";
				}

				return List.of(new ChatCompletionMessage(text, ChatCompletionMessage.Role.ASSISTANT, null, null,
						toolCalls, isPrefixAssistantMessage, reasoningContent));
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;

				toolMessage.getResponses()
					.forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));
				return toolMessage.getResponses()
					.stream()
					.map(tr -> new ChatCompletionMessage(tr.responseData(), ChatCompletionMessage.Role.TOOL, tr.name(),
							tr.id(), null))
					.toList();
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
			}
		}).flatMap(List::stream).toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

		DeepSeekChatOptions requestOptions = (DeepSeekChatOptions) prompt.getOptions();
		request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

		// Add the tool definitions to the request's tools parameter.
		List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			request = ModelOptionsUtils.merge(
					DeepSeekChatOptions.builder().tools(this.getFunctionTools(toolDefinitions)).build(), request,
					ChatCompletionRequest.class);
		}

		return request;
	}

	private List<DeepSeekApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new DeepSeekApi.FunctionTool.Function(toolDefinition.description(), toolDefinition.name(),
					toolDefinition.inputSchema());
			return new DeepSeekApi.FunctionTool(function);
		}).toList();
	}

	private ChatOptions buildRequestOptions(ChatCompletionRequest request) {
		return ChatOptions.builder()
			.model(request.model())
			.frequencyPenalty(request.frequencyPenalty())
			.maxTokens(request.maxTokens())
			.presencePenalty(request.presencePenalty())
			.stopSequences(request.stop())
			.temperature(request.temperature())
			.topP(request.topP())
			.build();
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return DeepSeekChatOptions.fromOptions(this.defaultOptions);
	}

	@Override
	public String toString() {
		return "DeepSeekV4ChatModel [defaultOptions=" + this.defaultOptions + "]";
	}

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 流式请求注入 stream_options.include_usage 的 WebClient 过滤器。
	 *
	 * <p>OpenAI 兼容网关（如 litellm）通常只在请求带 stream_options 时才在流式响应
	 * 的末帧携带 usage，而请求对象没有该字段，故在发送前于 WebClient 层补上。
	 * 非流式请求不受影响。
	 */
	static ExchangeFilterFunction streamUsageFilter() {
		return (request, next) -> {
			String path = request.url() == null ? "" : String.valueOf(request.url().getPath());
			if (request.method() != HttpMethod.POST || !path.contains("completions")) {
				return next.exchange(request);
			}
			ClientRequest patched = ClientRequest.from(request)
					.body((outputMessage, context) -> request.body()
							.insert(new StreamOptionsPatchingRequest(outputMessage), context))
					.build();
			return next.exchange(patched);
		};
	}

	/**
	 * 装饰请求写出：缓冲完整请求体后，stream=true 且无 stream_options 时注入再交给真实请求。
	 */
	private static final class StreamOptionsPatchingRequest implements ClientHttpRequest {

		private static final ObjectMapper JSON = new ObjectMapper();

		private final ClientHttpRequest delegate;

		private StreamOptionsPatchingRequest(ClientHttpRequest delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
			return DataBufferUtils.join(body)
					.map(buffer -> {
						try {
							byte[] bytes = new byte[buffer.readableByteCount()];
							buffer.read(bytes);
							return patchStreamOptions(bytes);
						}
						finally {
							DataBufferUtils.release(buffer);
						}
					})
					.defaultIfEmpty(new byte[0])
					.flatMap(patched -> delegate.writeWith(
							Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(patched))));
		}

		@Override
		public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
			return writeWith(Flux.from(body).flatMapSequential(Flux::from));
		}

		@Override
		public Mono<Void> setComplete() {
			return delegate.setComplete();
		}

		/** stream=true 且缺 stream_options 时注入 include_usage，其余原样返回。 */
		private static byte[] patchStreamOptions(byte[] original) {
			try {
				JsonNode root = JSON.readTree(original);
				if (root instanceof ObjectNode obj && obj.path("stream").asBoolean(false)
						&& !obj.has("stream_options")) {
					obj.putObject("stream_options").put("include_usage", true);
					return JSON.writeValueAsBytes(obj);
				}
			}
			catch (Exception e) {
				logger.warn("注入 stream_options 失败，按原请求体发送: {}", e.getMessage());
			}
			return original;
		}

		@Override
		public HttpHeaders getHeaders() {
			return delegate.getHeaders();
		}

		@Override
		public HttpMethod getMethod() {
			return delegate.getMethod();
		}

		@Override
		public URI getURI() {
			return delegate.getURI();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getNativeRequest() {
			return (T) delegate.getNativeRequest();
		}

		@Override
		public org.springframework.util.MultiValueMap<String, org.springframework.http.HttpCookie> getCookies() {
			return delegate.getCookies();
		}

		@Override
		public Map<String, Object> getAttributes() {
			return delegate.getAttributes();
		}

		@Override
		public boolean isCommitted() {
			return delegate.isCommitted();
		}

		@Override
		public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) {
			delegate.beforeCommit(action);
		}

		@Override
		public DataBufferFactory bufferFactory() {
			return delegate.bufferFactory();
		}
	}

	public static final class Builder {

		private DeepSeekApi deepSeekApi;

		/** 端点地址与密钥：不直接提供 DeepSeekApi 时由框架构建（自动应用 DeepSeek 网关适配）。 */
		private String baseUrl;

		private String apiKey;

		/** 可选：自定义 RestClient/WebClient（超时、TLS 等传输配置由调用方决定，缺省用默认值）。 */
		private RestClient.Builder restClientBuilder;

		private WebClient.Builder webClientBuilder;

		private DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
			.model(DeepSeekApi.DEFAULT_CHAT_MODEL)
			.temperature(0.7)
			.build();

		private ToolCallingManager toolCallingManager;

		private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate = new DefaultToolExecutionEligibilityPredicate();

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private Builder() {
		}

		public Builder deepSeekApi(DeepSeekApi deepSeekApi) {
			this.deepSeekApi = deepSeekApi;
			return this;
		}

		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder defaultOptions(DeepSeekChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder toolExecutionEligibilityPredicate(
				ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
			this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public DeepSeekV4ChatModel build() {
			if (this.deepSeekApi == null) {
				Assert.hasText(this.baseUrl, "baseUrl is required when deepSeekApi is not provided");
				WebClient.Builder wcb = (this.webClientBuilder != null ? this.webClientBuilder : WebClient.builder())
					.filter(streamUsageFilter());
				DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder()
					.apiKey(this.apiKey)
					.baseUrl(this.baseUrl)
					.webClientBuilder(wcb);
				if (this.restClientBuilder != null) {
					apiBuilder.restClientBuilder(this.restClientBuilder);
				}
				this.deepSeekApi = apiBuilder.build();
			}
			if (this.toolCallingManager != null) {
				return new DeepSeekV4ChatModel(this.deepSeekApi, this.defaultOptions, this.toolCallingManager,
						this.retryTemplate, this.observationRegistry, this.toolExecutionEligibilityPredicate);
			}
			return new DeepSeekV4ChatModel(this.deepSeekApi, this.defaultOptions, DEFAULT_TOOL_CALLING_MANAGER,
					this.retryTemplate, this.observationRegistry, this.toolExecutionEligibilityPredicate);
		}

	}

}
