package com.pm.drovi_backend.config;

import com.pm.drovi_backend.identity.DroviPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * An authenticated console caller, with our own principal rather than the raw JWT.
 *
 * <p>Controllers receive a {@link DroviPrincipal} carrying the local account id, so an
 * ownership check is a query scoped by account rather than a lookup someone might skip.
 */
public class DroviAuthenticationToken extends AbstractAuthenticationToken {

    private final transient DroviPrincipal principal;

    DroviAuthenticationToken(DroviPrincipal principal) {
        // A suspended account authenticates successfully and is then denied by the
        // authority check — 403, not 401. It knows who it is; it may just not act.
        super(principal.active()
                ? List.of(new SimpleGrantedAuthority(DroviPrincipal.ACTIVE_AUTHORITY))
                : List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public DroviPrincipal getPrincipal() {
        return principal;
    }

    /**
     * Never exposed. The bearer token is not kept past verification: anything holding it
     * can be logged, serialised into a session, or printed in a debug dump.
     */
    @Override
    public Object getCredentials() {
        return null;
    }
}
