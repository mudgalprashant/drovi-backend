package com.pm.drovi_backend.integration.fetch;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.config.DroviProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether a user-supplied URL may be fetched at all.
 *
 * <p>This is the whole reason fetching was avoided until now. A server that retrieves a URL a
 * stranger chose is a request-forgery engine pointed at its own network: the classic targets are
 * a cloud metadata endpoint handing out instance credentials, an internal admin service that
 * trusts anything reaching it, and the application itself.
 *
 * <p>So the rule is an <strong>allowlist of shapes</strong>, not a blocklist of strings. HTTPS
 * only, and every address the host resolves to must be a public one. Checking the hostname
 * against a list of bad names is the version of this that does not work — {@code 127.0.0.1} has
 * a great many spellings, and a domain that resolves to it has more.
 *
 * <h2>What this does not close</h2>
 *
 * <strong>DNS rebinding.</strong> The addresses are validated here and resolved again by the HTTP
 * client when it connects, and a hostile resolver can answer differently the second time. Closing
 * it properly means connecting to the validated address and carrying the hostname through SNI and
 * certificate verification by hand. It is not done, it is written down, and the far more common
 * attack — a redirect into private space — <em>is</em> closed, because every hop is re-checked.
 *
 * <p>A bean rather than a static utility so a test can substitute one that permits loopback.
 * There is deliberately <strong>no configuration flag</strong> to relax this in production: a
 * switch that turns off request-forgery protection is a switch that gets turned on during an
 * incident and left on.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlGuard {

    /**
     * Why a URL was refused.
     *
     * <p>A {@link DroviException} so it renders as a 400 with its own message rather than falling
     * to the generic 500 handler — every message here describes the <em>caller's</em> link and is
     * safe to show. "That link resolves to a private address" is exactly what someone who pasted
     * an internal URL by accident needs to read.
     *
     * <p>It also makes the failure terminal for a generation job: no retry turns a private
     * address into a public one.
     */
    public static class RefusedException extends DroviException {
        public RefusedException(String message) {
            super(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private final DroviProperties properties;

    public URI require(String candidate) {
        URI uri = parse(candidate);

        if (!"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            // HTTPS only. Plain HTTP would let anything between us and the host rewrite the
            // document we are about to build somebody's sandbox from.
            throw new RefusedException("Only https:// links can be read.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RefusedException("That link has no host.");
        }
        if (isOurOwnHost(host)) {
            // Fetching ourselves turns this into a way to reach our own routes from inside the
            // network boundary, which is where the interesting ones live.
            throw new RefusedException("That link points back at this service.");
        }
        requirePublicAddresses(host);
        return uri;
    }

    /** Re-checked on every redirect hop, which is how a public host sends us somewhere private. */
    public URI requireRedirect(URI from, String location) {
        return require(from.resolve(location).toString());
    }

    private void requirePublicAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RefusedException("That host does not resolve.");
        }
        if (addresses.length == 0) {
            throw new RefusedException("That host does not resolve.");
        }
        for (InetAddress address : addresses) {
            // EVERY address, not the first. A host resolving to one public and one private
            // address is a rebinding attempt wearing a disguise, and which one the client picks
            // is not ours to predict.
            if (isPrivate(address)) {
                log.warn("fetch.refused host={} reason=private-address", host);
                throw new RefusedException("That link resolves to a private address.");
            }
        }
    }

    /**
     * Everything that is not the public internet. Link-local covers the cloud metadata endpoints
     * — {@code 169.254.169.254} and its IPv6 equivalent — which are the single most valuable
     * thing a request forgery can reach.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            // 100.64.0.0/10, carrier-grade NAT — routable-looking, and not the public internet.
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 0.0.0.0/8, and 192.0.0.0/24 which is IETF protocol assignments rather than hosts.
            return first == 0 || (first == 192 && second == 0 && (octets[2] & 0xFF) == 0);
        }
        // IPv6 unique-local (fc00::/7) is the site-local equivalent Java does not report.
        return (octets[0] & 0xFE) == 0xFC;
    }

    private boolean isOurOwnHost(String host) {
        try {
            String ourHost = URI.create(properties.publicBaseUrl()).getHost();
            return ourHost != null && ourHost.equalsIgnoreCase(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static URI parse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new RefusedException("No link was given.");
        }
        try {
            URI uri = URI.create(candidate.trim());
            if (uri.getScheme() == null) {
                throw new RefusedException("That link needs to start with https://");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new RefusedException("That is not a valid link.");
        }
    }
}
