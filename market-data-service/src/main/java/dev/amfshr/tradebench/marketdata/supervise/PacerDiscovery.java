package dev.amfshr.tradebench.marketdata.supervise;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.rest.ApplicationAllowance;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.EventLog;

/**
 * Sets the REST budget after login from the tighter of the account's and our key's published
 * allowance, minus headroom — the account figure is shared by every key on the account, so
 * whichever is smaller is the one IG enforces first — recording both figures and the one used. Best-effort: an unanswered or failed read keeps the conservative start and
 * says so in the log and the event log — discovery never stops boot and never guesses another
 * key's budget.
 */
public final class PacerDiscovery {

    /** Requests per minute kept back from the published allowance — T6's heal spends the same key. */
    public static final int HEADROOM = 5;

    /** The read itself, so the discovery logic tests without HTTP. */
    public interface AllowanceSource {
        @Nullable ApplicationAllowance read(IgSession session) throws IOException, InterruptedException;
    }

    private static final String OPERATION = "GET /operations/application";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AllowanceSource source;
    private final IntConsumer applyBudget;
    private final EventLog events;
    private final Consumer<String> log;
    private final Clock clock;

    public PacerDiscovery(AllowanceSource source, IntConsumer applyBudget, EventLog events,
            Consumer<String> log, Clock clock) {
        this.source = source;
        this.applyBudget = applyBudget;
        this.events = events;
        this.log = log;
        this.clock = clock;
    }

    public void discover(IgSession session) throws InterruptedException {
        ApplicationAllowance allowance;
        try {
            allowance = source.read(session);
        } catch (IOException | RuntimeException e) {
            keepTheStart("pacer discovery failed — keeping the conservative start: " + e, e.toString());
            return;
        }
        if (allowance == null) {
            keepTheStart("pacer discovery: our API key is not listed by " + OPERATION
                    + " — keeping the conservative start", "our API key is not listed");
            return;
        }
        int account = allowance.allowanceAccountOverall();
        int application = allowance.allowanceApplicationOverall();
        int published = Math.min(account, application);
        int used = Math.max(1, published - HEADROOM);
        applyBudget.accept(used);
        log.accept("pacer: IG publishes " + account + "/min for the account and " + application
                + "/min for our key; using " + used + "/min (" + published + " − " + HEADROOM
                + " headroom)");
        record(ServiceEvent.of(EventType.PACER_DISCOVERED, clock.wallInstant())
                .withDetail(MAPPER.createObjectNode()
                        .put("account", account)
                        .put("application", application)
                        .put("published", published)
                        .put("used", used)
                        .put("headroom", HEADROOM)));
    }

    // The console learns why from the event log; stdout alone is not an alarm anyone watches.
    private void keepTheStart(String line, String why) {
        log.accept(line);
        record(ServiceEvent.of(EventType.IG_API_ERROR, clock.wallInstant())
                .withDetail(MAPPER.createObjectNode()
                        .put("operation", OPERATION)
                        .put("message", why)));
    }

    private void record(ServiceEvent event) {
        try {
            events.write(event);
        } catch (RuntimeException e) {
            log.accept("pacer event not written: " + e);
        }
    }
}
