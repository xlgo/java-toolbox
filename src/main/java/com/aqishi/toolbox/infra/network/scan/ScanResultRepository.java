package com.aqishi.toolbox.infra.network.scan;

import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.util.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One connection per store, short synchronized transactions. All callers run off the EDT. */
public final class ScanResultRepository implements AutoCloseable {
    public record Filter(String host, ProbeOutcome.State state, String search) {
        public Filter {
            host = host == null ? "" : host;
            search = search == null ? "" : search;
        }

        public static Filter all() {
            return new Filter("", null, "");
        }
    }

    public record Page(List<ProbeOutcome> rows, long total) {}

    public record Host(String source, String ip, long probed, long open, long errors) {}

    private final Path file;
    private final Connection database;
    private final long maxBytes;
    private final Thread shutdownHook;
    private long approximateBytes;
    private boolean closed;

    public ScanResultRepository(Path directory, int cacheMiB) throws Exception {
        Files.createDirectories(directory);
        file = Files.createTempFile(directory, "scan-", ".sqlite");
        maxBytes = (long) cacheMiB * 1024 * 1024;
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement sql = opened.createStatement()) {
                sql.execute("PRAGMA journal_mode=DELETE");
                sql.execute("PRAGMA cache_size=-2048");
                sql.execute("PRAGMA max_page_count=" + maxBytes / 4096);
                sql.execute(
                        "CREATE TABLE results(id INTEGER PRIMARY KEY,source TEXT,ip TEXT,port"
                            + " INTEGER,state TEXT,micros INTEGER,detail TEXT,stamp INTEGER,service"
                            + " TEXT)");
                sql.execute("CREATE INDEX result_host ON results(ip)");
                sql.execute("CREATE INDEX result_state ON results(state)");
                sql.execute(
                        "CREATE TABLE hosts(ip TEXT PRIMARY KEY,source TEXT,probed INTEGER,open"
                                + " INTEGER,errors INTEGER)");
            }
            database = opened;
            shutdownHook = new Thread(this::close, "portscan-store-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (Exception error) {
            if (opened != null) opened.close();
            Files.deleteIfExists(file);
            throw error;
        }
    }

    public Path path() {
        return file;
    }

    public synchronized void registerTargets(
            List<com.aqishi.toolbox.feature.network.domain.ScanTargetParser.Target> targets)
            throws SQLException, IOException {
        ensureOpen();
        database.setAutoCommit(false);
        try (PreparedStatement statement =
                database.prepareStatement("INSERT OR IGNORE INTO hosts VALUES(?,?,0,0,0)")) {
            for (var target : targets) {
                statement.setString(1, target.ip());
                statement.setString(2, target.source());
                statement.addBatch();
            }
            statement.executeBatch();
            database.commit();
        } catch (SQLException error) {
            database.rollback();
            throw error;
        } finally {
            database.setAutoCommit(true);
        }
    }

    public synchronized void append(List<ProbeOutcome> results) throws Exception {
        ensureOpen();
        long added =
                results.stream()
                        .mapToLong(
                                r ->
                                        240L
                                                + 2L
                                                        * (r.source().length()
                                                                + r.ip().length()
                                                                + r.detail().length()))
                        .sum();
        if (approximateBytes + added > maxBytes) throw new IOException("portscan.error.cacheFull");
        database.setAutoCommit(false);
        try (PreparedStatement insert =
                        database.prepareStatement(
                                "INSERT INTO"
                                    + " results(source,ip,port,state,micros,detail,stamp,service)"
                                    + " VALUES(?,?,?,?,?,?,?,?)");
                PreparedStatement host =
                        database.prepareStatement(
                                "INSERT INTO hosts VALUES(?,?,?,?,?) ON CONFLICT(ip) DO UPDATE SET"
                                    + " probed=probed+excluded.probed,open=open+excluded.open,errors=errors+excluded.errors")) {
            for (ProbeOutcome result : results) {
                insert.setString(1, result.source());
                insert.setString(2, result.ip());
                insert.setInt(3, result.port());
                insert.setString(4, result.state().name());
                insert.setLong(5, result.elapsedMicros());
                insert.setString(6, result.detail());
                insert.setLong(7, result.timestamp());
                insert.setString(8, PortPresets.service(result.port()));
                insert.addBatch();
                host.setString(1, result.ip().isEmpty() ? "dns:" + result.source() : result.ip());
                host.setString(2, result.source());
                host.setInt(3, result.port() > 0 ? 1 : 0);
                host.setInt(4, result.state() == ProbeOutcome.State.OPEN ? 1 : 0);
                host.setInt(5, result.state() == ProbeOutcome.State.OPEN ? 0 : 1);
                host.addBatch();
            }
            insert.executeBatch();
            host.executeBatch();
            database.commit();
            approximateBytes += added;
        } catch (Exception error) {
            database.rollback();
            if (error instanceof SQLException sql && sql.getErrorCode() == 13)
                throw new IOException("portscan.error.cacheFull", error);
            throw error;
        } finally {
            database.setAutoCommit(true);
        }
    }

    public synchronized Page page(Filter filter, long offset, int limit) throws Exception {
        ensureOpen();
        if (offset < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException();
        String where = where(filter);
        long count;
        try (PreparedStatement statement =
                database.prepareStatement("SELECT COUNT(*) FROM results" + where)) {
            bind(statement, filter);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                count = rows.getLong(1);
            }
        }
        ArrayList<ProbeOutcome> values = new ArrayList<>();
        try (PreparedStatement statement =
                database.prepareStatement(
                        "SELECT * FROM results" + where + " ORDER BY id LIMIT ? OFFSET ?")) {
            int i = bind(statement, filter);
            statement.setInt(i++, limit);
            statement.setLong(i, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(read(rows));
            }
        }
        return new Page(List.copyOf(values), count);
    }

    public synchronized List<Host> hosts() throws Exception {
        ensureOpen();
        ArrayList<Host> values = new ArrayList<>();
        try (Statement sql = database.createStatement();
                ResultSet rows = sql.executeQuery("SELECT * FROM hosts ORDER BY ip LIMIT 70000")) {
            while (rows.next())
                values.add(
                        new Host(
                                rows.getString("source"),
                                rows.getString("ip"),
                                rows.getLong("probed"),
                                rows.getLong("open"),
                                rows.getLong("errors")));
        }
        return values;
    }

    public synchronized long export(
            Path destination,
            boolean json,
            Filter filter,
            Map<String, Object> metadata,
            BooleanSupplier cancelled)
            throws Exception {
        ensureOpen();
        long count = 0;
        try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8);
                PreparedStatement statement =
                        database.prepareStatement(
                                "SELECT * FROM results" + where(filter) + " ORDER BY id")) {
            bind(statement, filter);
            com.fasterxml.jackson.core.JsonGenerator generator =
                    json ? Json.mapper().getFactory().createGenerator(writer) : null;
            if (json) {
                generator.writeStartObject();
                generator.writeObjectField("scan", metadata);
                generator.writeFieldName("results");
                generator.writeStartArray();
            } else {
                writer.write(
                        "source,ip,port,protocol,state,connect_ms,service,detail,timestamp,scan_started,timeout_ms,global_concurrency,per_host,rate,total,completed,phase\r\n");
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException();
                    ProbeOutcome r = read(rows);
                    count++;
                    if (json)
                        generator.writeObject(
                                Map.of(
                                        "source",
                                        r.source(),
                                        "ip",
                                        r.ip(),
                                        "port",
                                        r.port(),
                                        "protocol",
                                        "TCP",
                                        "state",
                                        r.state(),
                                        "connect_ms",
                                        r.elapsedMicros() / 1000.0,
                                        "service",
                                        rows.getString("service"),
                                        "detail",
                                        r.detail(),
                                        "timestamp",
                                        r.timestamp()));
                    else
                        writer.write(
                                String.join(
                                                ",",
                                                csv(r.source()),
                                                csv(r.ip()),
                                                Integer.toString(r.port()),
                                                "TCP",
                                                r.state().name(),
                                                Double.toString(r.elapsedMicros() / 1000.0),
                                                csv(rows.getString("service")),
                                                csv(r.detail()),
                                                Long.toString(r.timestamp()),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "started", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "timeoutMillis", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "concurrency", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "perHost", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault("rate", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "total", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "completed", ""))),
                                                csv(
                                                        String.valueOf(
                                                                metadata.getOrDefault(
                                                                        "phase", ""))))
                                        + "\r\n");
                }
            }
            if (json) {
                generator.writeEndArray();
                generator.writeEndObject();
                generator.close();
            }
            return count;
        }
    }

    public synchronized String copyOpen(Filter filter, int maxChars) throws Exception {
        ensureOpen();
        StringBuilder text = new StringBuilder();
        Filter open = new Filter(filter.host, ProbeOutcome.State.OPEN, filter.search);
        try (PreparedStatement sql =
                database.prepareStatement("SELECT * FROM results" + where(open) + " ORDER BY id")) {
            bind(sql, open);
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    text.append(read(rows).endpoint()).append('\n');
                    if (text.length() > maxChars) throw new IOException("portscan.error.copyLimit");
                }
            }
        }
        return text.toString();
    }

    private static String csv(String value) {
        // Prevent spreadsheet formulas from host labels and platform error strings.
        String leading = value.stripLeading();
        if (!leading.isEmpty() && "=+@-".indexOf(leading.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String where(Filter filter) {
        List<String> conditions = new ArrayList<>();
        if (!filter.host.isEmpty())
            conditions.add(filter.host.startsWith("dns:") ? "ip='' AND source=?" : "ip=?");
        if (filter.state != null) conditions.add("state=?");
        if (!filter.search.isEmpty())
            conditions.add(
                    "instr(lower(source||' '||ip||' '||port||' '||service||'"
                            + " '||detail),lower(?))>0");
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static int bind(PreparedStatement sql, Filter filter) throws SQLException {
        int i = 1;
        if (!filter.host.isEmpty())
            sql.setString(
                    i++, filter.host.startsWith("dns:") ? filter.host.substring(4) : filter.host);
        if (filter.state != null) sql.setString(i++, filter.state.name());
        if (!filter.search.isEmpty()) sql.setString(i++, filter.search);
        return i;
    }

    private static ProbeOutcome read(ResultSet row) throws SQLException {
        return new ProbeOutcome(
                row.getString("source"),
                row.getString("ip"),
                row.getInt("port"),
                ProbeOutcome.State.valueOf(row.getString("state")),
                row.getLong("micros"),
                row.getString("detail"),
                row.getLong("stamp"));
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Result store closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
        }
        try {
            database.close();
        } catch (SQLException ignored) {
        }
        for (String suffix : List.of("", "-journal", "-wal", "-shm"))
            try {
                Files.deleteIfExists(Path.of(file.toString() + suffix));
            } catch (IOException ignored) {
            }
    }
}
