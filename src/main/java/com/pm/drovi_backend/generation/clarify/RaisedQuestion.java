package com.pm.drovi_backend.generation.clarify;

import java.util.List;
import java.util.Map;

/**
 * Something a generation step was unsure about, as the step reports it.
 *
 * <p>Separate from {@link Clarification} because this is the <em>request</em> to ask, before it
 * has an id, a status or an answer.
 */
public record RaisedQuestion(String question,
                             String detail,
                             Map<String, Object> subject,
                             List<Clarification.Option> options,
                             boolean allowsAssumption) {

    /**
     * Reads what a model reported. Anything unusable is dropped rather than failing the job:
     * a malformed question is not worth losing a completed research step over, and the
     * generation simply proceeds with one fewer thing to ask.
     */
    @SuppressWarnings("unchecked")
    public static List<RaisedQuestion> from(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(map -> map.get("question") instanceof String text && !text.isBlank())
                .map(RaisedQuestion::one)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static RaisedQuestion one(Map<String, Object> map) {
        List<Clarification.Option> options = List.of();
        if (map.get("options") instanceof List<?> raw) {
            options = raw.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .filter(option -> option.get("label") instanceof String)
                    .map(option -> new Clarification.Option(
                            // The id is ours, not the model's: a console posts it back, and an
                            // id a model invented is one it can invent differently next time.
                            "opt" + (raw.indexOf(option) + 1),
                            String.valueOf(option.get("label")),
                            option.get("detail") instanceof String detail ? detail : null))
                    .toList();
        }
        return new RaisedQuestion(
                String.valueOf(map.get("question")),
                map.get("detail") instanceof String detail ? detail : null,
                map.get("subject") instanceof Map<?, ?> subject ? (Map<String, Object>) subject : Map.of(),
                options,
                // Default true: this is a mock, and for most doubts a plausible assumption
                // beats a blocked generation.
                !Boolean.FALSE.equals(map.get("allowsAssumption")));
    }
}
