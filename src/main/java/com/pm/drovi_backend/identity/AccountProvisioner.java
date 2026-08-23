package com.pm.drovi_backend.identity;

import com.pm.drovi_backend.domain.Account;
import com.pm.drovi_backend.repo.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a new account in its own transaction.
 *
 * <p>Separate from {@link AccountService} for one reason: a constraint violation marks the
 * <em>current</em> transaction rollback-only, so the losing side of a provisioning race
 * could not go on to read the winner's row if the insert shared its transaction. Isolating
 * the insert lets the caller catch the violation and continue.
 *
 * <p>It is also a separate bean because Spring's proxying means a self-invoked
 * {@code REQUIRES_NEW} method would silently run in the caller's transaction instead.
 */
@Component
@RequiredArgsConstructor
class AccountProvisioner {

    private final AccountRepository accounts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Account insert(String firebaseUid, String email, String displayName) {
        return accounts.saveAndFlush(Account.of(firebaseUid, email, displayName));
    }
}
