package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.integration.fetch.DocumentFetcher;
import com.pm.drovi_backend.integration.fetch.UrlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Turns a link into something a sandbox can be built from.
 *
 * <p>Two shapes of link, because users have two things in mind when they paste one:
 *
 * <ul>
 *   <li>A link <em>to a specification</em> — {@code https://example.com/openapi.json}. Fetched
 *       and read directly, and the generation needs no model call for its structure.
 *   <li>A link to <em>an API itself</em> — {@code https://api.example.com}. The document at the
 *       root is a landing page, not a spec, so the well-known locations are tried. This is the
 *       "give it a sandbox URL" case, and it is why the list exists.
 * </ul>
 *
 * <p>Anything else that came back is handed on as documentation and researched, exactly as if the
 * user had pasted it. Fetched text and pasted text are the same trust level — arbitrary content
 * from a third party — and the existing defence covers both: it reaches the model in the user
 * turn, and RESEARCH holds no tools.
 *
 * <p>The well-known probing is deliberately narrow. Trying five paths on every link would turn
 * one paste into five outbound requests, so it happens only when the link has no path of its own
 * and therefore cannot be pointing at a document.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecUrlResolver {

    /**
     * Where an API keeps its specification, in rough order of how often it is right. Kept short:
     * each entry is an outbound request against somebody else's server, and a long list of
     * guesses starts to look like scanning.
     */
    private static final List<String> WELL_KNOWN = List.of(
            "/openapi.json", "/swagger.json", "/v3/api-docs", "/.well-known/openapi.json");

    /**
     * What a link turned out to hold.
     *
     * @param specification a document the importer can read, or null
     * @param documentation whatever came back, when it was not a specification — researched
     */
    public record Resolved(URI source, String specification, String documentation) {

        public boolean isSpecification() {
            return specification != null;
        }
    }

    private final DocumentFetcher fetcher;
    private final SpecImporter importer;

    /**
     * @throws UrlGuard.RefusedException when the link cannot be opened. It renders as a 400 with
     *         a message describing the caller's own link
     */
    public Resolved resolve(String url) {
        DocumentFetcher.Document document = fetcher.fetch(url);
        if (importer.importableAs(document.body()).isPresent()) {
            return new Resolved(document.source(), document.body(), null);
        }

        Optional<Resolved> discovered = discoverFromRoot(document.source());
        if (discovered.isPresent()) {
            return discovered.get();
        }

        log.info("fetch.notASpec url={} — researching what came back instead", document.source());
        return new Resolved(document.source(), null, document.body());
    }

    /**
     * Only for a link with no path. {@code https://api.example.com} cannot be pointing at a
     * document, so the well-known locations are worth a look; {@code .../docs/guide} plainly is,
     * and guessing around it would be four requests to no purpose.
     */
    private Optional<Resolved> discoverFromRoot(URI source) {
        String path = source.getPath();
        if (path != null && !path.isBlank() && !path.equals("/")) {
            return Optional.empty();
        }
        for (String candidate : WELL_KNOWN) {
            String url = source.resolve(candidate).toString();
            try {
                DocumentFetcher.Document document = fetcher.fetch(url);
                if (importer.importableAs(document.body()).isPresent()) {
                    log.info("fetch.spec.discovered at={}", url);
                    return Optional.of(new Resolved(document.source(), document.body(), null));
                }
            } catch (UrlGuard.RefusedException e) {
                // A 404 at a guessed location is the expected answer, not a failure. Only the
                // link the user actually gave us is allowed to fail their request.
                log.debug("fetch.wellKnown.miss url={} detail={}", url, e.getMessage());
            }
        }
        return Optional.empty();
    }
}
