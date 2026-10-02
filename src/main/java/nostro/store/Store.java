package nostro.store;

import nostro.core.Event;

import java.util.List;

/** Append-only, hash-chained event log. */
public interface Store extends AutoCloseable {
    record Row(long seq, String prevHash, String hash, String line) {}

    long lastSeq();

    String lastHash();

    /** Appends all events atomically: either every event is durable or none is. */
    void append(List<Event> events);

    /** Rows with fromSeq <= seq <= toSeq, in order. */
    List<Row> read(long fromSeq, long toSeq);

    @Override void close();
}
