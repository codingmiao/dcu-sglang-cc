package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.wowtools.dcu.config.DcuConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite 连接工厂。
 * 统一建库目录、开连接（WAL + busy_timeout），供 {@link StatsStore} 与 {@link UserStore} 复用。
 */
@Component
@RequiredArgsConstructor
public class Sqlite {

    private final DcuConfiguration config;

    private String jdbcUrl;

    @PostConstruct
    public void init() throws Exception {
        String path = config.getStats().getSqlitePath();
        Path p = Path.of(path);
        if (p.toAbsolutePath().getParent() != null) {
            Files.createDirectories(p.toAbsolutePath().getParent());
        }
        jdbcUrl = "jdbc:sqlite:" + path;
    }

    /**
     * 开一个连接，设置 WAL 与 busy_timeout（避免 SQLITE_BUSY）。
     */
    public Connection open() throws Exception {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA busy_timeout=5000;");
        }
        return c;
    }
}
