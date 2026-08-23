package com.pm.drovi_backend.ai;

import java.util.Map;

/**
 * One model call, described in provider-neutral terms.
 *
 * <p>The split between {@code systemInstruction} and {@code userContent} is load-bearing
 * and not cosmetic. Everything Drovi decides goes in the system instruction; everything
 * that came from a user, a web page or a previous model output goes in {@code userContent}
 * — because researched and user-supplied text is <em>data, never instructions</em>. Merging
 * the two into one prompt is how a researched page gets to issue orders to a loop holding
 * database tools.
 *
 * @param responseSchema a JSON Schema the provider must shape its answer to, or {@code null}
 *                       for free text. Structured output is how a spec is parsed; scraping
 *                       JSON out of prose is not.
 */
public record AiRequest(AiPurpose purpose,
                        String systemInstruction,
                        String userContent,
                        Map<String, Object> responseSchema,
                        Integer maxOutputTokens) {

    public AiRequest {
        if (purpose == null) {
            throw new IllegalArgumentException("purpose is required — it selects the model and prices the call");
        }
        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("userContent is required");
        }
    }

    public static AiRequest of(AiPurpose purpose, String systemInstruction, String userContent) {
        return new AiRequest(purpose, systemInstruction, userContent, null, null);
    }

    public static AiRequest structured(AiPurpose purpose, String systemInstruction, String userContent,
                                       Map<String, Object> responseSchema) {
        return new AiRequest(purpose, systemInstruction, userContent, responseSchema, null);
    }

    public boolean wantsStructuredOutput() {
        return responseSchema != null && !responseSchema.isEmpty();
    }
}
