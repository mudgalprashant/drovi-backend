package com.pm.drovi_backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the {@code app_config} table — the values ops must be able to change during an
 * incident without waiting for a build.
 *
 * <p>Cached, because the mock runtime reads several of these on every request and the
 * table changes a few times a year. The cache is the reason {@link #refresh()} exists:
 * a 3am UPDATE that takes ten minutes to be noticed is not a kill switch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {

    private final JdbcTemplate jdbc;

    @Cacheable(value = "appConfig", key = "#key", unless = "#result == null")
    @Transactional(readOnly = true)
    public String get(String key) {
        return jdbc.query("SELECT value FROM app_config WHERE key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
    }

    /**
     * A missing or unparseable row falls back to the caller's default rather than failing
     * the request. A typo in a config value should degrade one setting, not take the
     * runtime down for every project at once.
     */
    public int getInt(String key, int fallback) {
        String raw = get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("config.unparseable key={} value={} using={}", key, raw, fallback);
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = get(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    @CacheEvict(value = "appConfig", allEntries = true)
    public void refresh() {
        log.info("config.refreshed");
    }
}
