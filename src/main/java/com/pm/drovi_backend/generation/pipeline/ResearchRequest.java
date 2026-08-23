package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.generation.TerminalJobException;

import java.util.Map;

/**
 * What a RESEARCH job was asked to find out.
 *
 * <p>Decision M, resolved 2026-08-23: <strong>documentation is recommended but not
 * mandatory.</strong> A caller either supplies docs, or explicitly opts into having the agent
 * research the product from its own knowledge. Supplying neither is not a third mode — it is
 * an incomplete request, and it fails saying so.
 *
 * <p>That distinction is the whole reason this is a record and not two nullable columns.
 * "No docs" and "no docs, and the user chose that" are different requests: the first is
 * someone who has not finished asking, the second is someone who accepted a known accuracy
 * trade-off. A silent fallback would erase the difference and quietly make the recommendation
 * decorative.
 *
 * @param product the product being imitated — "Stripe", "the Twilio SMS API"
 * @param docs    documentation pasted by the user. <strong>Untrusted.</strong> It reaches the
 *                model as data, never as instructions
 * @param docsUrl where the user says the docs came from. Recorded for provenance and shown
 *                back to them; <strong>nothing fetches it</strong>
 * @param agentResearchOnly the user's explicit acceptance that the agent works from its own
 *                knowledge of the product
 */
public record ResearchRequest(String product, String docs, String docsUrl, boolean agentResearchOnly) {

    private static final String MISSING_INPUT = "RESEARCH_INPUT_MISSING";

    public static ResearchRequest from(String prompt, Map<String, Object> input) {
        Map<String, Object> safe = input == null ? Map.of() : input;
        String product = text(safe.get("product"));
        return new ResearchRequest(
                product != null ? product : prompt,
                text(safe.get("docs")),
                text(safe.get("docsUrl")),
                Boolean.TRUE.equals(safe.get("agentResearchOnly")));
    }

    /**
     * @throws TerminalJobException because no retry supplies the documentation the caller did
     *         not send. Retrying an incomplete request just spends money reaching the same
     *         conclusion three times.
     */
    public void validate() {
        if (product == null || product.isBlank()) {
            throw new TerminalJobException(MISSING_INPUT,
                    "Tell us which product to imitate.");
        }
        if (!hasDocs() && !agentResearchOnly) {
            throw new TerminalJobException(MISSING_INPUT,
                    "Add the product's API documentation, or choose to have it researched for you. "
                            + "Supplying documentation gives a noticeably more accurate sandbox.");
        }
    }

    public boolean hasDocs() {
        return docs != null && !docs.isBlank();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
