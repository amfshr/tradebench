package dev.amfshr.tradebench.marketdata.capture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;

/** One JSON object per line; BigDecimals serialize with their exact wire scale (D36). */
public final class JsonlSink implements CaptureSink {

    private final Writer writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonlSink(Writer writer) {
        this.writer = writer;
    }

    /** First line of every capture file: who/where this data is for (user + source, PRD §7). */
    public void writeMeta(String user, String source, String instance, List<String> epics,
            Instant startedUtc) {
        ObjectNode node = mapper.createObjectNode()
                .put("kind", "meta")
                .put("user", user)
                .put("source", source)
                .put("instance", instance)
                .put("startedUtc", startedUtc.toString());
        node.putArray("epics").addAll(
                epics.stream().map(mapper.getNodeFactory()::textNode).toList());
        writeLine(node);
    }

    @Override
    public void write(Tick tick) {
        writeLine(mapper.createObjectNode()
                .put("kind", "tick")
                .put("epic", tick.epic())
                .put("utc", tick.timestamp().toString())
                .put("bid", tick.bid())
                .put("ask", tick.ask()));
    }

    @Override
    public void write(Bar1m bar) {
        ObjectNode node = mapper.createObjectNode()
                .put("kind", "bar")
                .put("epic", bar.epic())
                .put("startUtc", bar.startUtc().toString());
        putOhlc(node.putObject("bid"), bar.bid());
        putOhlc(node.putObject("ask"), bar.ask());
        if (bar.tickVolume() != null) {
            node.put("vol", bar.tickVolume());
        }
        writeLine(node);
    }

    @Override
    public void write(CaptureQueues.StateChange stateChange) {
        writeLine(mapper.createObjectNode()
                .put("kind", "state")
                .put("epic", stateChange.epic())
                .put("utc", stateChange.atUtc().toString())
                .put("dealFlag", stateChange.dealFlag()));
    }

    private void putOhlc(ObjectNode node, OhlcPrices prices) {
        node.put("o", prices.open())
                .put("h", prices.high())
                .put("l", prices.low())
                .put("c", prices.close());
    }

    private void writeLine(ObjectNode node) {
        try {
            writer.write(node.toString());
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("capture sink write failed", e);
        }
    }

    @Override
    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("capture sink flush failed", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("capture sink close failed", e);
        }
    }
}
