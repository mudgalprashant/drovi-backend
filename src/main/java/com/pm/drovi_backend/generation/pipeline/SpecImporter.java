package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.domain.SandboxProject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a pasted OpenAPI document or Postman collection into a sandbox, without asking a model.
 *
 * <h2>Why this is worth having</h2>
 *
 * When a user supplies the actual specification, research is not just unnecessary — it is
 * strictly worse. A model recalling Stripe's card API produces something close; the spec
 * <em>is</em> the API. Reading it directly is exact, free, and instant, and it removes the whole
 * class of "the sandbox is subtly wrong" problem that research invites.
 *
 * <p>So a recognised document skips RESEARCH and the SPEC model call entirely. What remains of
 * generation is seeding, which still needs a model because a spec says what a field is, not what
 * a plausible value looks like.
 *
 * <h2>What it does not do</h2>
 *
 * <ul>
 *   <li><strong>JSON only.</strong> YAML is the more common way to hand around an OpenAPI file,
 *       but reading it needs another dependency, and a half-working YAML parser that mangles
 *       anchors would be worse than an honest refusal. A user with YAML converts it once.
 *   <li><strong>No fetching.</strong> A {@code $ref} to another file, or a URL, is not followed —
 *       consistent with ADR-0010, and the same reasoning: nothing in this system retrieves a URL.
 *       Local {@code #/components/schemas/…} refs <em>are</em> resolved, since they are in the
 *       document the user pasted.
 * </ul>
 *
 * <p>Anything unrecognised returns empty, and the caller falls back to research. A paste that is
 * not a spec should cost the user nothing but the normal path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecImporter {

    /** What a document turned out to be. */
    public enum Format { OPENAPI, POSTMAN, NONE }

    private static final String LOCAL_SCHEMA_REF = "#/components/schemas/";
    private static final Set<String> METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options");

    private final ObjectMapper mapper;

    /**
     * Cheap enough to run on every request. Only decides <em>what</em> the document is; whether
     * it yields anything usable is {@link #parse}'s answer.
     */
    public Format detect(String document) {
        if (document == null || document.isBlank()) {
            return Format.NONE;
        }
        try {
            JsonNode root = mapper.readTree(document);
            if (!root.isObject()) {
                return Format.NONE;
            }
            if (root.has("openapi") || root.has("swagger")) {
                return Format.OPENAPI;
            }
            // Postman does not stamp a version field; the schema URL and an item array are what
            // identify it, and both are present in every collection v2 export.
            if (root.has("item") && root.path("info").path("schema").asString("").contains("postman")) {
                return Format.POSTMAN;
            }
            return Format.NONE;
        } catch (RuntimeException e) {
            // Not JSON, so not a spec we can read. Prose, or YAML — either way, research it.
            return Format.NONE;
        }
    }

    /**
     * The question a caller actually has: not "does this look like a spec" but "will reading it
     * produce a sandbox".
     *
     * <p>Those are different, and conflating them is a bug: an OpenAPI file with an empty
     * {@code paths} object is unmistakably OpenAPI and describes nothing. Routing on
     * {@link #detect} alone sent it down the import path, where it failed the user's generation
     * instead of falling back to research.
     *
     * <p>Parsing twice — once here, once in the handler — is deliberate. It is deterministic and
     * costs microseconds, and the alternative is carrying a parsed plan through a job's input as
     * a second representation of the same document.
     */
    public Optional<Format> importableAs(String document) {
        Format format = detect(document);
        return format != Format.NONE && parse(document).isPresent()
                ? Optional.of(format)
                : Optional.empty();
    }

    /**
     * @return the sandbox this document describes, or empty when it describes no usable routes —
     *         an OpenAPI file with no paths, a Postman collection of folders and no requests
     */
    Optional<SpecPlan> parse(String document) {
        try {
            return switch (detect(document)) {
                case OPENAPI -> fromOpenApi(mapper.readTree(document));
                case POSTMAN -> fromPostman(mapper.readTree(document));
                case NONE -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.info("spec.import.unreadable detail={}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- OpenAPI --------------------------------------------------------------

    private Optional<SpecPlan> fromOpenApi(JsonNode root) {
        List<SpecPlan.Endpoint> endpoints = new ArrayList<>();
        Set<String> resources = new LinkedHashSet<>();

        JsonNode paths = root.path("paths");
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            String template = path.getKey();
            String resource = resourceOf(template);
            if (resource == null) {
                continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String method = operation.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                resources.add(resource);
                endpoints.add(endpoint(template, method, resource,
                        operation.getValue().path("summary").asString(null),
                        groupOf(operation.getValue(), resource)));
            }
        }
        if (endpoints.isEmpty()) {
            return Optional.empty();
        }

        List<SpecPlan.Collection> collections = new ArrayList<>();
        for (String resource : resources) {
            collections.add(new SpecPlan.Collection(resource, capitalise(resource), null, "id",
                    schemaFor(root, resource)));
        }

        return validated(new SpecPlan(
                root.path("info").path("title").asString("Imported sandbox"),
                authModeOf(root),
                authHeaderOf(root),
                // A specification declares error SCHEMAS, not an example body, so there is
                // nothing here to build a template from. Drovi's shape it is.
                Map.of(),
                collections,
                endpoints));
    }

    /**
     * Best effort, and deliberately shallow. A component schema whose name matches the resource
     * gives the seeder real field names; anything cleverer would be guessing, and an empty schema
     * simply means SEED invents the fields instead.
     */
    private Map<String, Object> schemaFor(JsonNode root, String resource) {
        JsonNode schemas = root.path("components").path("schemas");
        for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
            String name = schema.getKey().toLowerCase(Locale.ROOT);
            if (name.equals(singular(resource)) || name.equals(resource)) {
                JsonNode properties = resolve(root, schema.getValue()).path("properties");
                if (properties.isObject()) {
                    return mapper.convertValue(properties, Map.class);
                }
            }
        }
        return Map.of();
    }

    /** One hop, and only inside this document. A `$ref` to a URL or another file is not followed. */
    private JsonNode resolve(JsonNode root, JsonNode node) {
        String ref = node.path("$ref").asString("");
        if (ref.startsWith(LOCAL_SCHEMA_REF)) {
            return root.path("components").path("schemas").path(ref.substring(LOCAL_SCHEMA_REF.length()));
        }
        return node;
    }

    private static SandboxProject.AuthMode authModeOf(JsonNode root) {
        for (Map.Entry<String, JsonNode> scheme : root.path("components").path("securitySchemes").properties()) {
            JsonNode value = scheme.getValue();
            String type = value.path("type").asString("").toLowerCase(Locale.ROOT);
            if (type.equals("http") && value.path("scheme").asString("").equalsIgnoreCase("bearer")) {
                return SandboxProject.AuthMode.BEARER;
            }
            if (type.equals("http") && value.path("scheme").asString("").equalsIgnoreCase("basic")) {
                return SandboxProject.AuthMode.BASIC;
            }
            if (type.equals("apikey")) {
                return SandboxProject.AuthMode.HEADER_KEY;
            }
        }
        // A spec that declares no security still gets BEARER, not NONE. A replica that waves
        // everything through never exercises the caller's auth path (decision #40), and an
        // omitted securityScheme is far more often an incomplete document than an open API.
        return SandboxProject.AuthMode.BEARER;
    }

    private static String authHeaderOf(JsonNode root) {
        for (Map.Entry<String, JsonNode> scheme : root.path("components").path("securitySchemes").properties()) {
            if (scheme.getValue().path("type").asString("").equalsIgnoreCase("apiKey")
                    && scheme.getValue().path("in").asString("").equalsIgnoreCase("header")) {
                return scheme.getValue().path("name").asString(null);
            }
        }
        return null;
    }

    private static String groupOf(JsonNode operation, String fallback) {
        JsonNode tags = operation.path("tags");
        return tags.isArray() && !tags.isEmpty() ? tags.get(0).asString(fallback) : capitalise(fallback);
    }

    // --- Postman --------------------------------------------------------------

    private Optional<SpecPlan> fromPostman(JsonNode root) {
        List<SpecPlan.Endpoint> endpoints = new ArrayList<>();
        Set<String> resources = new LinkedHashSet<>();
        collectPostman(root.path("item"), root.path("info").path("name").asString("Imported"),
                endpoints, resources);

        if (endpoints.isEmpty()) {
            return Optional.empty();
        }
        List<SpecPlan.Collection> collections = new ArrayList<>();
        for (String resource : resources) {
            // A Postman collection carries example requests, not schemas, so the seeder is told
            // nothing about fields and invents them. Still exact about the ROUTES, which is the
            // part research gets wrong.
            collections.add(new SpecPlan.Collection(resource, capitalise(resource), null, "id", Map.of()));
        }
        return validated(new SpecPlan(
                root.path("info").path("name").asString("Imported sandbox"),
                SandboxProject.AuthMode.BEARER, null, Map.of(), collections, endpoints));
    }

    /** Folders nest arbitrarily deep, and the folder name is the group. */
    private void collectPostman(JsonNode items, String group,
                                List<SpecPlan.Endpoint> endpoints, Set<String> resources) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item.has("item")) {
                collectPostman(item.path("item"), item.path("name").asString(group), endpoints, resources);
                continue;
            }
            JsonNode request = item.path("request");
            String method = request.path("method").asString("").toLowerCase(Locale.ROOT);
            String template = postmanPath(request.path("url"));
            if (!METHODS.contains(method) || template == null) {
                continue;
            }
            String resource = resourceOf(template);
            if (resource == null) {
                continue;
            }
            resources.add(resource);
            endpoints.add(endpoint(template, method, resource,
                    item.path("name").asString(null), group));
        }
    }

    /**
     * Postman stores a path as segments and marks variables with a leading colon; ours uses
     * braces. Everything else is left exactly as written — the path is the product's.
     */
    private static String postmanPath(JsonNode url) {
        JsonNode segments = url.path("path");
        if (!segments.isArray() || segments.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (JsonNode segment : segments) {
            String value = segment.asString("");
            if (value.isBlank()) {
                continue;
            }
            path.append('/').append(value.startsWith(":") ? "{" + value.substring(1) + "}" : value);
        }
        return path.isEmpty() ? null : path.toString();
    }

    /**
     * An imported plan gets exactly the same checks as a generated one. A specification can
     * describe a route the runtime cannot serve — a duplicated path, a record endpoint with no
     * parameter — just as easily as a model can invent one, and finding out at request time is
     * no better for coming from a real document.
     *
     * <p>A document that fails returns empty and the caller falls back to research, rather than
     * failing the user's request outright.
     */
    private Optional<SpecPlan> validated(SpecPlan plan) {
        try {
            plan.validate();
            return Optional.of(plan);
        } catch (RuntimeException e) {
            log.info("spec.import.rejected detail={}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- shared ---------------------------------------------------------------

    /**
     * Which collection an endpoint serves: the last path segment that is not a parameter.
     * {@code /v1/customers/{id}/cards} is cards, not customers — the thing being listed is the
     * thing at the end.
     */
    private static String resourceOf(String template) {
        String[] segments = template.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (!segment.isBlank() && !segment.startsWith("{") && !segment.startsWith(":")) {
                return segment.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * A spec says what a route is, not what it does with data. GET on a path ending in a
     * parameter is one record; GET on the bare path is many; the write verbs map straight over.
     */
    private static SpecPlan.Endpoint endpoint(String template, String method, String resource,
                                              String summary, String group) {
        boolean addressesOne = template.trim().endsWith("}");
        ApiEndpoint.Behavior behavior = switch (method) {
            case "post" -> ApiEndpoint.Behavior.CREATE;
            case "put", "patch" -> ApiEndpoint.Behavior.UPDATE;
            case "delete" -> ApiEndpoint.Behavior.DELETE;
            case "get" -> addressesOne ? ApiEndpoint.Behavior.GET : ApiEndpoint.Behavior.LIST;
            // HEAD and OPTIONS back no data. STATIC is the honest answer rather than pretending.
            default -> ApiEndpoint.Behavior.STATIC;
        };
        // Named explicitly rather than left to be inferred. Real specs nest —
        // /v1/customers/{customerId}/cards/{cardId} has two parameters, and the record being
        // addressed is the last one. Inference only handles the single-parameter case.
        String keyParam = addressesOne || behavior == ApiEndpoint.Behavior.UPDATE
                || behavior == ApiEndpoint.Behavior.DELETE
                ? lastPlaceholder(template)
                : null;

        return new SpecPlan.Endpoint(
                method.toUpperCase(Locale.ROOT), template, group,
                summary == null || summary.isBlank() ? method.toUpperCase(Locale.ROOT) + " " + template : summary,
                behavior,
                behavior == ApiEndpoint.Behavior.STATIC ? null : resource,
                keyParam, Map.of(), null);
    }

    private static String lastPlaceholder(String template) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(template);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private static String singular(String word) {
        return word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
