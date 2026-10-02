package nostro.store;

import nostro.core.Canon;
import nostro.core.Event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite as the durability layer, deliberately. WAL mode plus synchronous=FULL plus fullfsync (macOS F_FULLFSYNC)
 * gives atomic commit and crash recovery that have survived more real crashes than a hand-rolled log ever will.
 * The chain hash on top is ours: it makes the history tamper-evident and lets replay verify what it reads.
 */
public final class SqliteStore implements Store {
    private final Connection db;
    private final PreparedStatement insert;
    private long lastSeq;
    private String lastHash;

    public SqliteStore(String path) {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA fullfsync=ON");
                st.execute("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY, prev_hash TEXT NOT NULL, hash TEXT NOT NULL, line TEXT NOT NULL)");
                try (ResultSet rs = st.executeQuery("SELECT seq, hash FROM events ORDER BY seq DESC LIMIT 1")) {
                    if (rs.next()) { lastSeq = rs.getLong(1); lastHash = rs.getString(2); }
                    else { lastSeq = 0; lastHash = Canon.GENESIS; }
                }
            }
            insert = db.prepareStatement("INSERT INTO events(seq, prev_hash, hash, line) VALUES (?, ?, ?, ?)");
            db.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open store at " + path, e);
        }
    }

    @Override public long lastSeq() { return lastSeq; }

    @Override public String lastHash() { return lastHash; }

    @Override public void append(List<Event> events) {
        if (events.isEmpty()) return;
        long seq = lastSeq;
        String prev = lastHash;
        try {
            for (Event e : events) {
                String line = Canon.encode(e);
                String hash = Canon.chain(prev, line);
                insert.setLong(1, ++seq);
                insert.setString(2, prev);
                insert.setString(3, hash);
                insert.setString(4, line);
                insert.executeUpdate();
                prev = hash;
            }
            db.commit();
        } catch (SQLException e) {
            try { db.rollback(); } catch (SQLException ignored) { /* original error is the one to report */ }
            throw new IllegalStateException("append failed; nothing was written", e);
        }
        lastSeq = seq;
        lastHash = prev;
    }

    @Override public List<Row> read(long fromSeq, long toSeq) {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT seq, prev_hash, hash, line FROM events WHERE seq BETWEEN ? AND ? ORDER BY seq")) {
            ps.setLong(1, fromSeq);
            ps.setLong(2, toSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Test/demo hook: corrupts one stored line in place, bypassing the chain. verify-chain must catch it. */
    public void tamper(long seq, String newLine) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE events SET line = ? WHERE seq = ?")) {
            ps.setString(1, newLine);
            ps.setLong(2, seq);
            ps.executeUpdate();
            db.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void close() {
        try { db.close(); } catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
