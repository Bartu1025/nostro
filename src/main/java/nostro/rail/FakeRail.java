package nostro.rail;

import nostro.core.Ccy;
import nostro.core.Command.RailOutcome;
import nostro.core.Ids.TransferId;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A bank that sometimes does not answer. On TIMEOUT it may or may not have moved the money - exactly like a real one -
 * and only its statement, later, tells you which. Seeded, so every run of the simulator sees the same bank.
 */
public final class FakeRail implements RailPort {
    public record Line(String ref, long amount, Ccy ccy) {}

    private final Random rnd;
    private final double pFail, pTimeout;
    /** What the bank actually did. This is the ground truth the statement is generated from. */
    public final List<Line> processed = new ArrayList<>();
    private boolean crashAfterNext;

    public FakeRail(long seed, double pFail, double pTimeout) {
        this.rnd = new Random(seed);
        this.pFail = pFail;
        this.pTimeout = pTimeout;
    }

    @Override public RailOutcome send(TransferId id, long amount, Ccy ccy, String ref) {
        double x = rnd.nextDouble();
        RailOutcome out = x < pFail ? RailOutcome.FAILED : x < pFail + pTimeout ? RailOutcome.TIMEOUT : RailOutcome.OK;
        boolean moved = out == RailOutcome.OK || (out == RailOutcome.TIMEOUT && rnd.nextBoolean());
        if (moved) processed.add(new Line(id.v(), amount, ccy));
        if (crashAfterNext) {
            crashAfterNext = false;
            throw new IllegalStateException("simulated process crash after the rail accepted " + id);
        }
        return out;
    }

    /** The next send() will succeed on the bank's side and then kill our process before we can log the result. */
    public void crashAfterNextSend() { crashAfterNext = true; }

    public String statementCsv() {
        StringBuilder sb = new StringBuilder("ref,amount_minor,ccy\n");
        for (Line l : processed) sb.append(l.ref).append(',').append(l.amount).append(',').append(l.ccy).append('\n');
        return sb.toString();
    }
}
