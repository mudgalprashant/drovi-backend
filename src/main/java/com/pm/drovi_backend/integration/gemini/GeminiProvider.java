package com.pm.drovi_backend.integration.gemini;

import com.pm.drovi_backend.ai.AiProvider;
import com.pm.drovi_backend.ai.AiProviderException;
import com.pm.drovi_backend.ai.AiRequest;
import com.pm.drovi_backend.ai.AiResponse;
import com.pm.drovi_backend.ai.ProviderConfig;
import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Gemini, over its REST API.
 *
 * <p>The bean name is the contract: {@code ai_provider_config.adapter_bean} holds the string
 * {@code geminiProvider}, and that row is how the platform finds this class. Renaming the
 * bean without the matching UPDATE takes generation down with an "adapter bean does not
 * exist" failure.
 *
 * <p>Everything vendor-shaped stops at this package — request envelope, response envelope,
 * finish reasons, header name. What leaves is {@link AiResponse} or
 * {@link AiProviderException}, so switching provider stays a database change.
 *
 * <p>⚠️ Model ids are <em>not</em> written here. They come from {@code app_config} routing
 * (see {@code ModelRouter}) precisely because Google's ids move fast and many are
 * {@code -preview}. A wrong id fails at request time, not at startup, so it must be fixable
 * with an UPDATE.
 */
@Component("geminiProvider")
@RequiredArgsConstructor
@Slf4j
class GeminiProvider implements AiProvider {

    private static final int CONNECT_TIMEOUT_DEFAULT_SECONDS = 10;
    private static final int READ_TIMEOUT_DEFAULT_SECONDS = 120;

    /**
     * Finish reasons that mean the model declined rather than broke. Retrying one of these
     * with the same prompt just spends money to be told no a second time, which is why they
     * are REFUSED and not ERROR — the job runner retries ERROR.
     */
    private static final Set<String> REFUSAL_REASONS =
            Set.of("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY");

    private final AppConfigService config;
    private final ObjectMapper mapper;

    @Override
    public AiResponse complete(ProviderConfig provider, String apiKey, String model, AiRequest request) {
        String url = "%s/v1beta/models/%s:generateContent".formatted(trimTrailingSlash(provider.baseUrl()), model);
        String payload = mapper.writeValueAsString(body(provider, request));

        String raw;
        try {
            raw = client().post()
                    .uri(url)
                    // Gemini takes its key in its own header, not an Authorization bearer.
                    // The header NAME comes from the config row, so a provider that moves to
                    // a different scheme is a migration rather than a code change.
                    .header(provider.authHeaderName(), apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

        } catch (RestClientResponseException e) {
            // The upstream body is logged, never rethrown: it can echo the prompt back, and
            // the prompt can contain a user's own material.
            log.warn("gemini.http.error status={} model={}", e.getStatusCode().value(), model);
            throw AiProviderException.error(
                    "Gemini returned HTTP %d".formatted(e.getStatusCode().value()), e);

        } catch (RestClientException e) {
            // The cause chain, not the exception type, is what identifies a timeout. A read
            // timeout that fires while the body is still arriving is wrapped as a plain
            // RestClientException rather than a ResourceAccessException, so matching on the
            // type alone ledgers half of all timeouts as ERROR — and ERROR is what the job
            // runner retries.
            if (hasCause(e, SocketTimeoutException.class)) {
                // The request may well have been served and billed; only our half gave up.
                throw AiProviderException.timeout("Gemini did not answer within the read timeout", e);
            }
            throw AiProviderException.error("Gemini was unreachable, or its response could not be read", e);
        }

        return parse(raw, model);
    }

    /**
     * Built per call rather than held as a field, so a timeout change in {@code app_config}
     * takes effect on the next call instead of the next deploy. The factory wraps a plain
     * connection and costs microseconds — nothing next to a call measured in seconds.
     */
    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(
                config.getInt("ai.http.connect.timeout.seconds", CONNECT_TIMEOUT_DEFAULT_SECONDS)));
        // The single most important line in this class. Without a read timeout a hung
        // provider parks a worker thread for as long as it likes, and the symptom is the
        // whole app going quiet rather than generation failing.
        factory.setReadTimeout(Duration.ofSeconds(
                config.getInt("ai.http.read.timeout.seconds", READ_TIMEOUT_DEFAULT_SECONDS)));
        return RestClient.builder().requestFactory(factory).build();
    }

    private Map<String, Object> body(ProviderConfig provider, AiRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();

        // systemInstruction is a SEPARATE field from contents, and that separation is the
        // structural half of the prompt-injection defence: researched pages and user text go
        // into contents as data. Concatenating them into one prompt is what lets a scraped
        // page issue instructions to a loop holding database tools.
        if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        }
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", request.userContent())))));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens",
                request.maxOutputTokens() != null ? request.maxOutputTokens() : provider.maxOutputTokens());
        if (request.wantsStructuredOutput()) {
            generationConfig.put("responseMimeType", MediaType.APPLICATION_JSON_VALUE);
            generationConfig.put("responseSchema", request.responseSchema());
        }
        body.put("generationConfig", generationConfig);
        return body;
    }

    private AiResponse parse(String raw, String model) {
        JsonNode root;
        try {
            root = mapper.readTree(raw == null ? "" : raw);
        } catch (RuntimeException e) {
            // Jackson 3's parse failures are unchecked, so this catch is RuntimeException by
            // necessity, not by laziness.
            throw AiProviderException.error("Gemini's response was not JSON", e);
        }

        // A prompt blocked before generation has no candidates at all — only promptFeedback.
        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (!blockReason.isMissingNode() && !blockReason.isNull()) {
            throw AiProviderException.refused("Gemini blocked the prompt: " + blockReason.asString());
        }

        JsonNode candidate = root.path("candidates").path(0);
        String finishReason = candidate.path("finishReason").asString("");
        if (REFUSAL_REASONS.contains(finishReason)) {
            throw AiProviderException.refused("Gemini declined to answer: " + finishReason);
        }

        String text = textOf(candidate);
        if (text.isBlank()) {
            // MAX_TOKENS lands here when the whole budget went to reasoning: a truncated
            // structured answer is unusable, and ERROR is right because a retry can succeed.
            throw AiProviderException.error(
                    "Gemini returned no text (finishReason=%s, model=%s)".formatted(finishReason, model), null);
        }

        JsonNode usage = root.path("usageMetadata");
        return new AiResponse(text,
                usage.path("promptTokenCount").asInt(0),
                // Reasoning tokens are billed as output but reported separately, so a ledger
                // that counts only candidatesTokenCount under-reports every call.
                usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0));
    }

    /** A candidate's text can arrive split across several parts; concatenation is the answer. */
    private static String textOf(JsonNode candidate) {
        List<String> chunks = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            JsonNode text = part.path("text");
            if (text.isString()) {
                chunks.add(text.asString());
            }
        }
        return String.join("", chunks);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
