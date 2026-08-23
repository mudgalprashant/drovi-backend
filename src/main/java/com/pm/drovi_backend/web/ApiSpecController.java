package com.pm.drovi_backend.web;

import com.pm.drovi_backend.domain.ApiCollection;
import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.domain.ResponseRule;
import com.pm.drovi_backend.identity.DroviPrincipal;
import com.pm.drovi_backend.project.ApiSpecService;
import com.pm.drovi_backend.project.RuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Postman-like view: API groups, endpoints, and the rules that override them.
 *
 * <p>Completes the loop the console was missing — a project can now have routes without
 * anyone touching SQL.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
class ApiSpecController {

    private final ApiSpecService spec;
    private final RuleService rules;

    // --- requests ------------------------------------------------------------

    record CreateGroupRequest(@NotBlank @Size(max = 120) String name,
                              @Size(max = 500) String description) {
    }

    record CreateEndpointRequest(@NotBlank String method,
                                 @NotBlank String pathTemplate,
                                 /* Name of an API group; created if it does not exist.
                                    Ignored when apiGroupId is given. */
                                 @Size(max = 120) String apiGroup,
                                 UUID apiGroupId,
                                 @Size(max = 200) String summary,
                                 @Size(max = 1000) String description,
                                 ApiEndpoint.Behavior behavior,
                                 UUID dataCollectionId,
                                 @Size(max = 60) String keyParam,
                                 Map<String, Object> responseTemplate,
                                 Integer successStatus) {
    }

    record UpdateEndpointRequest(String method, String pathTemplate,
                                 @Size(max = 200) String summary,
                                 @Size(max = 1000) String description,
                                 ApiEndpoint.Behavior behavior, UUID dataCollectionId,
                                 @Size(max = 60) String keyParam,
                                 Map<String, Object> responseTemplate, Integer successStatus) {
    }

    record CreateRuleRequest(@Size(max = 120) String name, Integer priority,
                             Map<String, Object> matcher, Integer statusCode,
                             Map<String, Object> headers, Map<String, Object> body,
                             Integer delayMs, Integer remainingUses, Instant expiresAt) {
    }

    record UpdateRuleRequest(@Size(max = 120) String name, Integer priority, Boolean enabled,
                             Map<String, Object> matcher, Integer statusCode,
                             Map<String, Object> headers, Map<String, Object> body,
                             Integer delayMs, Integer remainingUses) {
    }

    // --- responses -----------------------------------------------------------

    record GroupResponse(String id, String name, String description, Instant createdAt) {
    }

    record EndpointResponse(String id, String apiGroupId, String method, String pathTemplate,
                            String summary, String description, String behavior,
                            String dataCollectionId, String keyParam,
                            Map<String, Object> responseTemplate, int successStatus,
                            int specificity, Instant createdAt) {
    }

    record RuleResponse(String id, String endpointId, String name, int priority, boolean enabled,
                        Map<String, Object> matcher, int statusCode, Map<String, Object> headers,
                        Map<String, Object> body, int delayMs, Integer remainingUses,
                        Instant expiresAt, Instant createdAt) {
    }

    // --- API groups ----------------------------------------------------------

    @GetMapping("/api-groups")
    List<GroupResponse> listGroups(@AuthenticationPrincipal DroviPrincipal principal,
                                   @PathVariable UUID projectId) {
        return spec.listGroups(principal.accountId(), projectId).stream()
                .map(ApiSpecController::toResponse).toList();
    }

    @PostMapping("/api-groups")
    @ResponseStatus(HttpStatus.CREATED)
    GroupResponse createGroup(@AuthenticationPrincipal DroviPrincipal principal,
                              @PathVariable UUID projectId,
                              @Valid @RequestBody CreateGroupRequest request) {
        return toResponse(spec.createGroup(principal.accountId(), projectId,
                request.name(), request.description()));
    }

    @DeleteMapping("/api-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGroup(@AuthenticationPrincipal DroviPrincipal principal,
                     @PathVariable UUID projectId, @PathVariable UUID groupId) {
        spec.deleteGroup(principal.accountId(), projectId, groupId);
    }

    // --- endpoints -----------------------------------------------------------

    @GetMapping("/endpoints")
    List<EndpointResponse> listEndpoints(@AuthenticationPrincipal DroviPrincipal principal,
                                         @PathVariable UUID projectId) {
        return spec.listEndpoints(principal.accountId(), projectId).stream()
                .map(ApiSpecController::toResponse).toList();
    }

    @PostMapping("/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    EndpointResponse createEndpoint(@AuthenticationPrincipal DroviPrincipal principal,
                                    @PathVariable UUID projectId,
                                    @Valid @RequestBody CreateEndpointRequest r) {
        return toResponse(spec.createEndpoint(principal.accountId(), projectId, r.apiGroup(),
                r.apiGroupId(), r.method(), r.pathTemplate(), r.summary(), r.description(),
                r.behavior(), r.dataCollectionId(), r.keyParam(), r.responseTemplate(),
                r.successStatus()));
    }

    @GetMapping("/endpoints/{endpointId}")
    EndpointResponse getEndpoint(@AuthenticationPrincipal DroviPrincipal principal,
                                 @PathVariable UUID projectId, @PathVariable UUID endpointId) {
        return toResponse(spec.requireEndpoint(principal.accountId(), projectId, endpointId));
    }

    @PatchMapping("/endpoints/{endpointId}")
    EndpointResponse updateEndpoint(@AuthenticationPrincipal DroviPrincipal principal,
                                    @PathVariable UUID projectId, @PathVariable UUID endpointId,
                                    @Valid @RequestBody UpdateEndpointRequest r) {
        return toResponse(spec.updateEndpoint(principal.accountId(), projectId, endpointId,
                r.method(), r.pathTemplate(), r.summary(), r.description(), r.behavior(),
                r.dataCollectionId(), r.keyParam(), r.responseTemplate(), r.successStatus()));
    }

    @DeleteMapping("/endpoints/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteEndpoint(@AuthenticationPrincipal DroviPrincipal principal,
                        @PathVariable UUID projectId, @PathVariable UUID endpointId) {
        spec.deleteEndpoint(principal.accountId(), projectId, endpointId);
    }

    // --- rules ---------------------------------------------------------------

    @GetMapping("/endpoints/{endpointId}/rules")
    List<RuleResponse> listRules(@AuthenticationPrincipal DroviPrincipal principal,
                                 @PathVariable UUID projectId, @PathVariable UUID endpointId) {
        return rules.list(principal.accountId(), projectId, endpointId).stream()
                .map(ApiSpecController::toResponse).toList();
    }

    @PostMapping("/endpoints/{endpointId}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleResponse createRule(@AuthenticationPrincipal DroviPrincipal principal,
                            @PathVariable UUID projectId, @PathVariable UUID endpointId,
                            @Valid @RequestBody CreateRuleRequest r) {
        return toResponse(rules.create(principal.accountId(), projectId, endpointId, r.name(),
                r.priority(), r.matcher(), r.statusCode(), r.headers(), r.body(),
                r.delayMs(), r.remainingUses(), r.expiresAt()));
    }

    @PatchMapping("/rules/{ruleId}")
    RuleResponse updateRule(@AuthenticationPrincipal DroviPrincipal principal,
                            @PathVariable UUID projectId, @PathVariable UUID ruleId,
                            @Valid @RequestBody UpdateRuleRequest r) {
        return toResponse(rules.update(principal.accountId(), projectId, ruleId, r.name(),
                r.priority(), r.enabled(), r.matcher(), r.statusCode(), r.headers(),
                r.body(), r.delayMs(), r.remainingUses()));
    }

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRule(@AuthenticationPrincipal DroviPrincipal principal,
                    @PathVariable UUID projectId, @PathVariable UUID ruleId) {
        rules.delete(principal.accountId(), projectId, ruleId);
    }

    // --- mapping -------------------------------------------------------------

    private static GroupResponse toResponse(ApiCollection g) {
        return new GroupResponse(g.getId().toString(), g.getName(), g.getDescription(), g.getCreatedAt());
    }

    private static EndpointResponse toResponse(ApiEndpoint e) {
        return new EndpointResponse(e.getId().toString(), e.getCollectionId().toString(),
                e.getMethod(), e.getPathTemplate(), e.getSummary(), e.getDescription(),
                e.getBehavior().name(),
                e.getDataCollectionId() == null ? null : e.getDataCollectionId().toString(),
                e.getKeyParam(), e.getResponseTemplate(), e.getSuccessStatus(),
                // Exposed read-only: it explains WHY one route wins over another, which is
                // otherwise invisible and looks like a bug to whoever hits it.
                e.getSpecificity(), e.getCreatedAt());
    }

    private static RuleResponse toResponse(ResponseRule r) {
        return new RuleResponse(r.getId().toString(), r.getEndpointId().toString(), r.getName(),
                r.getPriority(), r.isEnabled(), r.getMatcher(), r.getStatusCode(), r.getHeaders(),
                r.getBody(), r.getDelayMs(), r.getRemainingUses(), r.getExpiresAt(), r.getCreatedAt());
    }
}
