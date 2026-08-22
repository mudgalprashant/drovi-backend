package com.pm.drovi_backend.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills an endpoint's response envelope.
 *
 * <p>Products disagree about envelopes — Stripe wraps a list in
 * {@code {"object":"list","data":[…],"has_more":false}}, others return a bare array, others
 * nest under {@code {"result":…}}. Rather than special-case each one, the generated spec
 * stores the envelope as a template and this fills in the holes, so a new product's shape
 * is data the agent writes, never code somebody has to add.
 *
 * <p>Two substitution modes, chosen by whether the placeholder is the whole string:
 * {@code "data": "{{items}}"} becomes the actual array, while
 * {@code "url": "/v1/cards/{{recordKey}}"} becomes interpolated text. Without the first
 * mode a JSON template could never produce a non-string value.
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    /**
     * @param template the envelope; empty means the product uses none, so {@code fallback}
     *                 is returned verbatim
     */
    public Object render(Map<String, Object> template, Map<String, Object> values, Object fallback) {
        if (template == null || template.isEmpty()) {
            return fallback;
        }
        return substitute(template, values);
    }

    private Object substitute(Object node, Map<String, Object> values) {
        return switch (node) {
            case String s -> substituteString(s, values);
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, values)));
                yield out;
            }
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                list.forEach(v -> out.add(substitute(v, values)));
                yield out;
            }
            case null, default -> node;
        };
    }

    private Object substituteString(String s, Map<String, Object> values) {
        Matcher whole = PLACEHOLDER.matcher(s);
        if (whole.matches()) {
            // The placeholder IS the value: return the object, preserving its JSON type.
            // An unknown name yields null rather than the literal "{{foo}}", so a template
            // referring to something the behaviour did not produce degrades to a null
            // field instead of leaking template syntax to the caller.
            return values.get(whole.group(1));
        }
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
