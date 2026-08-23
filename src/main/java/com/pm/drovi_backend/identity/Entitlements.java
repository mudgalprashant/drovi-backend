package com.pm.drovi_backend.identity;

/**
 * What a plan permits. Read from {@code plan_catalog}, never from the client.
 *
 * <p>INVARIANT: limits are server-authoritative. A client that computes its own
 * entitlement is a client that can be edited, so this record is something the console
 * <em>displays</em> and never something it asserts.
 */
public record Entitlements(String planCode,
                           String displayName,
                           int maxProjects,
                           int maxEndpointsPerProject,
                           long maxRecordsPerProject,
                           long maxStoredBytesPerProject,
                           long maxMockRequestsPerMonth,
                           long maxAiTokensPerMonth,
                           int maxGenerationsPerMonth,
                           int logRetentionDays,
                           int priceMinor,
                           String currency) {
}
