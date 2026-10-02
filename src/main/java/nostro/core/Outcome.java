package nostro.core;

import java.util.List;

public sealed interface Outcome {
    /** Events to append, in order. May be empty (e.g. a Tick with nothing to expire). */
    record Applied(List<Event> events) implements Outcome {}

    /** Same id, same payload: already happened. State unchanged. */
    record Replayed() implements Outcome {}

    /** State unchanged. Rejects never consume an id: re-sending a rejected command re-evaluates it. */
    record Rejected(Reject reason, String detail) implements Outcome {}

    Replayed REPLAYED = new Replayed();

    static Applied applied(Event... events) { return new Applied(List.of(events)); }

    static Rejected reject(Reject r, String detail) { return new Rejected(r, detail); }
}
