package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.domain.ApiCollection;
import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.identity.AccountService;
import com.pm.drovi_backend.identity.EntitlementService;
import com.pm.drovi_backend.repo.ApiCollectionRepository;
import com.pm.drovi_backend.repo.ApiEndpointRepository;
import com.pm.drovi_backend.repo.SandboxCollectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The spec: API groups and the endpoints in them.
 *
 * <p>This is the piece that lets a project have routes without SQL. Until it existed, a
 * console-created sandbox answered 404 to everything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiSpecService {

    private static final String DEFAULT_GROUP = "Default";

    private final ApiCollectionRepository groups;
    private final ApiEndpointRepository endpoints;
    private final SandboxCollectionRepository dataCollections;
    private final ProjectService projects;
    private final AccountService accounts;
    private final EntitlementService entitlements;

    // --- API groups ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ApiCollection> listGroups(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return groups.findByProjectIdOrderBySortOrderAscNameAsc(projectId);
    }

    @Transactional
    public ApiCollection createGroup(UUID accountId, UUID projectId, String name, String description) {
        projects.require(accountId, projectId);
        groups.findByProjectIdAndName(projectId, name).ifPresent(existing -> {
            throw new DroviException(ErrorCode.CONFLICT,
                    "An API group called '%s' already exists in this project.".formatted(name));
        });
        return groups.save(ApiCollection.create(projectId, name, description));
    }

    /**
     * Deleting a group cascades to its endpoints, and their rules. Refused while it still
     * holds endpoints — a single click that silently removes a working route and every
     * override on it is not a thing to offer.
     */
    @Transactional
    public void deleteGroup(UUID accountId, UUID projectId, UUID groupId) {
        projects.require(accountId, projectId);
        ApiCollection group = groups.findByIdAndProjectId(groupId, projectId)
                .orElseThrow(() -> DroviException.notFound("No such API group."));
        long held = endpoints.findByCollectionId(groupId).size();
        if (held > 0) {
            throw new DroviException(ErrorCode.CONFLICT,
                    "This API group still holds %d endpoint(s). Delete or move them first.".formatted(held));
        }
        groups.delete(group);
    }

    // --- endpoints -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ApiEndpoint> listEndpoints(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return endpoints.findByProjectIdOrderByCollectionIdAscSortOrderAscPathTemplateAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ApiEndpoint requireEndpoint(UUID accountId, UUID projectId, UUID endpointId) {
        projects.require(accountId, projectId);
        return endpoints.findByIdAndProjectId(endpointId, projectId)
                .orElseThrow(() -> DroviException.notFound("No such endpoint."));
    }

    @Transactional
    public ApiEndpoint createEndpoint(UUID accountId, UUID projectId, String apiGroup, UUID apiGroupId,
                                      String method, String pathTemplate, String summary,
                                      String description, ApiEndpoint.Behavior behavior,
                                      UUID dataCollectionId, String keyParam,
                                      Map<String, Object> responseTemplate, Integer successStatus) {
        projects.require(accountId, projectId);

        int max = entitlements.forPlan(accounts.require(accountId).getPlanCode()).maxEndpointsPerProject();
        if (endpoints.countByProjectId(projectId) >= max) {
            throw new DroviException(ErrorCode.QUOTA_EXCEEDED,
                    "This plan allows %d endpoints per project.".formatted(max));
        }

        String normalisedMethod = requireMethod(method);
        String path = requirePath(pathTemplate);
        endpoints.findByProjectIdAndMethodAndPathTemplate(projectId, normalisedMethod, path)
                .ifPresent(existing -> {
                    throw new DroviException(ErrorCode.CONFLICT,
                            "%s %s already exists in this project.".formatted(normalisedMethod, path));
                });

        UUID group = resolveGroup(projectId, apiGroup, apiGroupId);
        UUID data = requireDataCollection(projectId, behavior, dataCollectionId);

        ApiEndpoint created = endpoints.save(ApiEndpoint.create(projectId, group, normalisedMethod, path,
                summary == null || summary.isBlank() ? normalisedMethod + " " + path : summary,
                description, behavior, data, keyParam, Map.of(), Map.of(),
                responseTemplate, successStatus));
        log.info("endpoint.created endpointId={} projectId={} route={} {}",
                created.getId(), projectId, normalisedMethod, path);
        return created;
    }

    @Transactional
    public ApiEndpoint updateEndpoint(UUID accountId, UUID projectId, UUID endpointId, String method,
                                      String pathTemplate, String summary, String description,
                                      ApiEndpoint.Behavior behavior, UUID dataCollectionId,
                                      String keyParam, Map<String, Object> responseTemplate,
                                      Integer successStatus) {
        ApiEndpoint endpoint = requireEndpoint(accountId, projectId, endpointId);
        String newMethod = method == null ? endpoint.getMethod() : requireMethod(method);
        String newPath = pathTemplate == null ? endpoint.getPathTemplate() : requirePath(pathTemplate);

        if (!newMethod.equals(endpoint.getMethod()) || !newPath.equals(endpoint.getPathTemplate())) {
            endpoints.findByProjectIdAndMethodAndPathTemplate(projectId, newMethod, newPath)
                    .filter(other -> !other.getId().equals(endpointId))
                    .ifPresent(other -> {
                        throw new DroviException(ErrorCode.CONFLICT,
                                "%s %s already exists in this project.".formatted(newMethod, newPath));
                    });
        }

        ApiEndpoint.Behavior newBehavior = behavior == null ? endpoint.getBehavior() : behavior;
        UUID newData = dataCollectionId != null ? dataCollectionId : endpoint.getDataCollectionId();
        requireDataCollection(projectId, newBehavior, newData);

        endpoint.update(newMethod, newPath, summary, description, behavior, dataCollectionId,
                keyParam, responseTemplate, successStatus);
        return endpoint;
    }

    @Transactional
    public void deleteEndpoint(UUID accountId, UUID projectId, UUID endpointId) {
        ApiEndpoint endpoint = requireEndpoint(accountId, projectId, endpointId);
        endpoints.delete(endpoint);
        log.info("endpoint.deleted endpointId={} projectId={}", endpointId, projectId);
    }

    // --- validation ----------------------------------------------------------

    private static String requireMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED, "method is required.");
        }
        String upper = method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(upper)) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED, "Unsupported method: " + method);
        }
        return upper;
    }

    /**
     * The path is the imitated product's, stored verbatim — casing included. The only rule
     * is that it starts with '/', because the runtime matches against a path that always
     * does. Nothing else is normalised: rewriting a caller's path would break the one
     * promise the product makes.
     */
    private static String requirePath(String pathTemplate) {
        if (pathTemplate == null || pathTemplate.isBlank()) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED, "pathTemplate is required.");
        }
        String path = pathTemplate.trim();
        if (!path.startsWith("/")) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "pathTemplate must start with '/' — it is the real product's path, verbatim.");
        }
        return path;
    }

    /**
     * A data-backed endpoint needs a collection, and that collection must belong to the
     * <em>same project</em>. The database's composite key would catch a cross-project
     * reference, but catching it here turns a constraint violation into a clear 400.
     */
    private UUID requireDataCollection(UUID projectId, ApiEndpoint.Behavior behavior, UUID dataCollectionId) {
        if (behavior == null || behavior == ApiEndpoint.Behavior.STATIC) {
            return dataCollectionId;
        }
        if (dataCollectionId == null) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "behavior %s reads data, so dataCollectionId is required.".formatted(behavior));
        }
        dataCollections.findByIdAndProjectId(dataCollectionId, projectId)
                .orElseThrow(() -> new DroviException(ErrorCode.VALIDATION_FAILED,
                        "No such data collection in this project."));
        return dataCollectionId;
    }

    /** Find by id, else find-or-create by name, else fall back to a default group. */
    private UUID resolveGroup(UUID projectId, String apiGroup, UUID apiGroupId) {
        if (apiGroupId != null) {
            return groups.findByIdAndProjectId(apiGroupId, projectId)
                    .orElseThrow(() -> DroviException.notFound("No such API group."))
                    .getId();
        }
        String name = apiGroup == null || apiGroup.isBlank() ? DEFAULT_GROUP : apiGroup.trim();
        return groups.findByProjectIdAndName(projectId, name)
                .orElseGet(() -> groups.save(ApiCollection.create(projectId, name, null)))
                .getId();
    }
}
