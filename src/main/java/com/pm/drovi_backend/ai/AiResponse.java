package com.pm.drovi_backend.ai;

/**
 * What a provider returned, reduced to the three things the platform cares about: the
 * text, and the two token counts that price it.
 *
 * <p>Token counts are the provider's own report, never an estimate. Pricing a call from a
 * local guess at tokenisation makes the ledger a work of fiction, and invariant 3 exists
 * to make the ledger the thing you can trust when the bill is a surprise.
 */
public record AiResponse(String text, int inputTokens, int outputTokens) {

    public AiResponse {
        text = text == null ? "" : text;
        inputTokens = Math.max(inputTokens, 0);
        outputTokens = Math.max(outputTokens, 0);
    }
}
