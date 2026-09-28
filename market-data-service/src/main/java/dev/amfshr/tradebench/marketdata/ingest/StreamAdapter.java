package dev.amfshr.tradebench.marketdata.ingest;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;

/**
 * The anti-corruption boundary: the only place IG stream shapes meet the domain. DLG_FLAG
 * deliberately does not cross — market state routes to state machinery, never per-tick
 * storage (T3 design ruling).
 */
public final class StreamAdapter {

    private StreamAdapter() {
    }

    public static Tick toDomain(TickUpdate update) {
        return new Tick(update.epic(), update.timestampUtc(), update.bid(), update.ask());
    }

    public static Bar1m toDomain(SealedBarUpdate update) {
        return new Bar1m(update.epic(), update.startUtc(), toDomain(update.bid()),
                toDomain(update.offer()), update.lastTradedVolume());
    }

    private static OhlcPrices toDomain(Ohlc ohlc) {
        return new OhlcPrices(ohlc.open(), ohlc.high(), ohlc.low(), ohlc.close());
    }
}
