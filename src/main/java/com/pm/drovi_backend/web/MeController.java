package com.pm.drovi_backend.web;

import com.pm.drovi_backend.domain.Account;
import com.pm.drovi_backend.identity.AccountService;
import com.pm.drovi_backend.identity.DroviPrincipal;
import com.pm.drovi_backend.identity.EntitlementService;
import com.pm.drovi_backend.identity.Entitlements;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The caller's own identity and limits.
 *
 * <p>Every value here is derived from the authenticated principal. Nothing is taken from
 * the request — there is no account id in the path, precisely so there is no id to forge.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MeController {

    private final AccountService accounts;
    private final EntitlementService entitlements;

    record MeResponse(String accountId,
                      String email,
                      String displayName,
                      String planCode,
                      Instant createdAt) {
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal DroviPrincipal principal) {
        Account account = accounts.require(principal.accountId());
        return new MeResponse(
                account.getId().toString(),
                account.getEmail(),
                account.getDisplayName(),
                account.getPlanCode(),
                account.getCreatedAt());
    }

    /**
     * What this account is allowed to do. The console <em>displays</em> these; it never
     * enforces them. A limit checked only on the client is not a limit.
     */
    @GetMapping("/entitlements")
    Entitlements entitlements(@AuthenticationPrincipal DroviPrincipal principal) {
        return entitlements.forPlan(accounts.require(principal.accountId()).getPlanCode());
    }
}
