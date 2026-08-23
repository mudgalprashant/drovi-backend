package com.pm.drovi_backend.identity;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.domain.Account;
import com.pm.drovi_backend.repo.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Turns a verified token into a local account, creating one on first sight.
 *
 * <p>There is no signup endpoint, deliberately. Firebase already decided who this person
 * is; asking them to register again would be a second source of truth for identity and a
 * second place for it to go wrong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accounts;
    private final AccountProvisioner provisioner;

    @Transactional
    public DroviPrincipal resolve(VerifiedIdentity identity) {
        Account account = accounts.findByFirebaseUid(identity.firebaseUid())
                .orElseGet(() -> provision(identity));

        account.touch(identity.email(), identity.displayName(), Instant.now());

        // Status is REPORTED, not enforced: this runs inside the security filter chain,
        // where a thrown exception never reaches @RestControllerAdvice. Authorization is
        // an authority check in SecurityConfig, which produces a clean 403.
        return new DroviPrincipal(account.getId(), account.getFirebaseUid(),
                account.getEmail(), account.isActive());
    }

    /** The caller's own record. Always resolved by id from the principal, never from input. */
    @Transactional(readOnly = true)
    public Account require(java.util.UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> DroviException.notFound("Account not found."));
    }

    /**
     * Two devices signing in at once both miss the read and both insert. The unique index
     * on {@code firebase_uid} settles it: the loser catches the violation and reads the
     * winner's row.
     *
     * <p>Do <em>not</em> "fix" this into a check-then-insert. That is the race this is
     * written to survive, not a bug in it.
     */
    private Account provision(VerifiedIdentity identity) {
        try {
            Account created = provisioner.insert(
                    identity.firebaseUid(), identity.email(), identity.displayName());
            log.info("account.provisioned accountId={}", created.getId());
            return created;
        } catch (DataIntegrityViolationException lostTheRace) {
            return accounts.findByFirebaseUid(identity.firebaseUid())
                    .orElseThrow(() -> lostTheRace);
        }
    }
}
