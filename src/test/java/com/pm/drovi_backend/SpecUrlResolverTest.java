package com.pm.drovi_backend;

import com.pm.drovi_backend.generation.pipeline.SpecImporter;
import com.pm.drovi_backend.generation.pipeline.SpecUrlResolver;
import com.pm.drovi_backend.integration.fetch.DocumentFetcher;
import com.pm.drovi_backend.integration.fetch.UrlGuard;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a pasted link turns out to be.
 *
 * <p>Users mean two different things by "here is the URL": a link to a specification, and a link
 * to an API. The second is the one the product goal calls a sandbox URL, and it is why the
 * well-known locations are tried at all.
 *
 * <p>No network — the fetcher is stubbed with canned documents, because what is under test is the
 * decision tree, not HTTP. {@link DocumentFetcherTest} covers the fetching.
 */
class SpecUrlResolverTest {

    private static final String OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"Cards"},
             "paths":{"/v1/cards":{"get":{"summary":"List"}}}}""";

    private final Map<String, String> hosted = new HashMap<>();
    private final List<String> requested = new ArrayList<>();

    /** Serves whatever the test put in {@link #hosted}, and refuses everything else as a 404 would. */
    private final DocumentFetcher fetcher = new DocumentFetcher(null, null) {
        @Override
        public Document fetch(String url) {
            requested.add(url);
            String body = hosted.get(url);
            if (body == null) {
                throw new UrlGuard.RefusedException("That link answered with HTTP 404.");
            }
            return new Document(URI.create(url), "application/json", body, false);
        }
    };

    private final SpecUrlResolver resolver =
            new SpecUrlResolver(fetcher, new SpecImporter(JsonMapper.builder().build()));

    // --- a link to a specification --------------------------------------------

    @Test
    void aLinkStraightToASpec_isReadAsOne() {
        hosted.put("https://example.test/openapi.json", OPENAPI);

        SpecUrlResolver.Resolved resolved = resolver.resolve("https://example.test/openapi.json");

        assertThat(resolved.isSpecification()).isTrue();
        assertThat(resolved.specification()).contains("/v1/cards");
    }

    /** A link with a path of its own is pointing at a document; guessing around it is four wasted requests. */
    @Test
    void aLinkWithItsOwnPath_isNotProbedFurther() {
        hosted.put("https://example.test/docs/guide", "<html>Read our guide</html>");

        SpecUrlResolver.Resolved resolved = resolver.resolve("https://example.test/docs/guide");

        assertThat(resolved.isSpecification()).isFalse();
        assertThat(requested).containsExactly("https://example.test/docs/guide");
    }

    // --- a link to an API: the sandbox-URL case -------------------------------

    /**
     * The product goal's "give it a sandbox url". The root is a landing page, so the well-known
     * locations are tried and the spec is found without the user knowing where it lives.
     */
    @Test
    void aBareApiUrl_findsTheSpecAtAWellKnownLocation() {
        hosted.put("https://api.example.test", "<html>Welcome to our API</html>");
        hosted.put("https://api.example.test/openapi.json", OPENAPI);

        SpecUrlResolver.Resolved resolved = resolver.resolve("https://api.example.test");

        assertThat(resolved.isSpecification()).isTrue();
        assertThat(resolved.source().toString()).isEqualTo("https://api.example.test/openapi.json");
    }

    @Test
    void aBareApiUrl_alsoLooksWhereSpringAndSwaggerPutIt() {
        hosted.put("https://api.example.test/", "<html>hi</html>");
        hosted.put("https://api.example.test/v3/api-docs", OPENAPI);

        assertThat(resolver.resolve("https://api.example.test/").isSpecification()).isTrue();
    }

    /** A miss at a guessed location is the expected answer, not a failure of the user's request. */
    @Test
    void whenNoWellKnownLocationHasASpec_whatCameBackIsResearchedInstead() {
        hosted.put("https://api.example.test", "<html>Our API is documented in the PDF</html>");

        SpecUrlResolver.Resolved resolved = resolver.resolve("https://api.example.test");

        assertThat(resolved.isSpecification()).isFalse();
        assertThat(resolved.documentation()).contains("documented in the PDF");
    }

    /** Bounded on purpose: a long list of guesses against somebody else's server is scanning. */
    @Test
    void probing_isBoundedToAShortList() {
        hosted.put("https://api.example.test", "<html>hi</html>");

        resolver.resolve("https://api.example.test");

        assertThat(requested).hasSizeLessThanOrEqualTo(5);
    }

    // --- failures -------------------------------------------------------------

    /** Only the link the user actually gave is allowed to fail their request. */
    @Test
    void whenTheGivenLinkCannotBeRead_theFailureReachesTheCaller() {
        assertThatThrownBy(() -> resolver.resolve("https://example.test/gone"))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("404");
    }

    /** A Postman collection is a specification too, and takes the same shortcut. */
    @Test
    void aPostmanCollectionAtALink_isReadAsASpecification() {
        hosted.put("https://example.test/collection.json", """
                {"info":{"name":"Payments","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item":[{"name":"List","request":{"method":"GET","url":{"path":["v1","charges"]}}}]}""");

        assertThat(resolver.resolve("https://example.test/collection.json").isSpecification()).isTrue();
    }
}
