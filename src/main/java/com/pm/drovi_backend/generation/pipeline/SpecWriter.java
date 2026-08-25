package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.generation.TerminalJobException;
import com.pm.drovi_backend.project.ApiSpecService;
import com.pm.drovi_backend.project.ProjectService;
import com.pm.drovi_backend.project.SandboxDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a validated {@link SpecPlan} into a project, all of it or none of it.
 *
 * <h2>Why one transaction</h2>
 *
 * A project with three of its eight routes looks finished, so the user integrates against it
 * and meets the missing five as 404s from their own code. Rolling the lot back and failing the
 * job is the kinder outcome, and it is the only one that keeps "generate" idempotent enough to
 * retry.
 *
 * <p>This is safe to make transactional precisely because the model call has already happened:
 * the handler talks to the provider first, with nothing open, and hands the result here. No
 * network round trip occurs inside this method.
 *
 * <h2>Why it goes through the console's own services</h2>
 *
 * {@link ApiSpecService} and {@link SandboxDataService} already enforce ownership, plan limits,
 * verbatim path storage and the same-project data binding. Generation writing rows directly
 * would be a second write path that has to be kept in step with the first, and the way those
 * diverge is that one of them quietly stops checking something.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecWriter {

    private final ProjectService projects;
    private final SandboxDataService data;
    private final ApiSpecService spec;

    /**
     * @return how many collections and endpoints landed, for the job's result
     * @throws TerminalJobException when the project already has structure. Generating over the
     *         top of an existing sandbox is a REVISE, not a SPEC, and doing it silently would
     *         duplicate every route the user had already corrected by hand
     */
    @Transactional
    public Map<String, Object> write(UUID accountId, UUID projectId, SpecPlan plan) {
        projects.require(accountId, projectId);

        if (!data.listCollections(accountId, projectId).isEmpty()
                || !spec.listEndpoints(accountId, projectId).isEmpty()) {
            throw new TerminalJobException("PROJECT_NOT_EMPTY",
                    "This project already has endpoints or data. Generate into a new project, "
                            + "or ask for a change to this one.");
        }

        Map<String, UUID> collectionIds = new HashMap<>();
        for (SpecPlan.Collection collection : plan.collections()) {
            SandboxCollection created = data.createCollection(accountId, projectId,
                    collection.code(), collection.displayName(), collection.description(),
                    collection.recordSchema(), collection.keyField());
            collectionIds.put(collection.code(), created.getId());
        }

        for (SpecPlan.Endpoint raw : plan.endpoints()) {
            SpecPlan.Endpoint endpoint = SpecPlan.withInferredKeyParam(raw);
            spec.createEndpoint(accountId, projectId,
                    endpoint.group(), null,
                    endpoint.method(), endpoint.path(), endpoint.summary(), null,
                    endpoint.behavior(), collectionIds.get(endpoint.collection()),
                    endpoint.keyParam(), endpoint.responseTemplate(), endpoint.successStatus());
        }

        // The replica authenticates its own callers (decision #40): a sandbox that waves
        // everything through never exercises the integration's auth path, which is one of the
        // things it exists to let someone test. That is a property of the imitation, so
        // generation decides it.
        //
        // The NAME is not. Passing null leaves whatever the user called this project alone —
        // they chose it, and having a generation quietly rename their work is the kind of
        // helpfulness nobody asked for. The suggestion is returned instead, for the console to
        // offer.
        projects.update(accountId, projectId, null, plan.authMode(), plan.authHeaderName(), null);

        // Thread N: a replica whose 404 is Drovi-shaped is not faithful on the branch a caller
        // most wants to test. Set from what research found, never hardcoded for one product.
        projects.useErrorEnvelope(accountId, projectId, plan.errorEnvelope());

        log.info("spec.written projectId={} collections={} endpoints={}",
                projectId, plan.collections().size(), plan.endpoints().size());
        return Map.of(
                "suggestedName", plan.projectName() == null ? "" : plan.projectName(),
                "authMode", plan.authMode().name(),
                "collections", plan.collections().stream().map(SpecPlan.Collection::code).toList(),
                // By id as well as by code, so whatever seeds these does not have to go back to
                // the database and work out which of a project's collections were SPEC's doing.
                "collectionIds", collectionIds.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().toString())),
                "errorEnvelope", !plan.errorEnvelope().isEmpty(),
                "endpointCount", plan.endpoints().size());
    }
}
