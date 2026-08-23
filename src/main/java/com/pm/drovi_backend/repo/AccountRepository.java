package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByFirebaseUid(String firebaseUid);
}
