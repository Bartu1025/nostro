package nostro;

import nostro.rail.FakeRail;
import nostro.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The determinism claim as a single constant. Runs in CI on Linux and macOS: if any ambient input ever leaks into
 * the core - map order, clock, locale, JDK-version-specific RNG - this is the test that goes red.
 */
class GoldenHashTest {
    static final String GOLDEN = "3a1ea65da7ac1c65ddce388be36ad1319e31a4684772000fd7d80284624ccb61";

    @Test
    void seed_42_over_10k_commands_always_hashes_the_same() throws Exception {
        Path db = Files.createTempFile("nostro-golden", ".db");
        Files.delete(db);
        db.toFile().deleteOnExit();
        try (SqliteStore store = new SqliteStore(db.toString())) {
            FakeRail rail = new FakeRail(43, 0.05, 0.10);
            Engine e = new Engine(store, rail);
            Simulation sim = new Simulation(42, e, rail);
            sim.setup();
            sim.steps(10_000);
            e.state.checkInvariants();
            assertEquals(GOLDEN, e.state.hash());
        }
    }
}
