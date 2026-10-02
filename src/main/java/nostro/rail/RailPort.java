package nostro.rail;

import nostro.core.Ccy;
import nostro.core.Command.RailOutcome;
import nostro.core.Ids.TransferId;

/** The external boundary. Everything behind it is someone else's computer: it can say yes, no, or nothing. */
public interface RailPort {
    RailOutcome send(TransferId id, long amount, Ccy ccy, String ref);
}
