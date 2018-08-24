/**
 * Copyright (C) 2018 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package net.spy.memcached;

import io.opencensus.common.Scope;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.Aggregation.Distribution;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.stats.View;
import io.opencensus.stats.View.Name;
import io.opencensus.stats.View.AggregationWindow.Cumulative;
import io.opencensus.stats.ViewManager;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagContextBuilder;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Observability {
    private static final StatsRecorder statsRecorder = Stats.getStatsRecorder();
    private static final Tagger tagger = Tags.getTagger();
    private static final Tracer tracer = Tracing.getTracer();

    // Units of measurement
    private static final String BYTES = "By";
    private static final String DIMENSIONLESS = "1";
    private static final String MILLISECONDS = "ms";

    // Tag keys
    public static final TagKey keyMethod = TagKey.create("method");
    public static final TagKey keyPhase  = TagKey.create("phase");
    public static final TagKey keyReason = TagKey.create("reason");
    public static final TagKey keyType   = TagKey.create("type");

    // Measures
    public static final MeasureLong mCalls       = MeasureLong.create("spymemcached/calls", "The number of calls to the server", DIMENSIONLESS);
    public static final MeasureLong mCacheHits   = MeasureLong.create("spymemcached/cache_hits", "Records the number of cache hits", DIMENSIONLESS);
    public static final MeasureLong mCacheMisses = MeasureLong.create("spymemcached/cache_misses", "Records the number of cache misses", DIMENSIONLESS);
    public static final MeasureLong mErrors      = MeasureLong.create("spymemcached/errors", "The number of errors encountered", DIMENSIONLESS);
    public static final MeasureLong mKeyLength   = MeasureLong.create("spymemcached/key_length", "Records the lengths of keys", DIMENSIONLESS);
    public static final MeasureDouble mLatencyMs = MeasureDouble.create("spymemcached/latency", "The latency of calls in milliseconds", MILLISECONDS);
    public static final MeasureLong mValueLength = MeasureLong.create("spymemcached/value_length", "Records the lengths of values", DIMENSIONLESS);

    public static void recordTaggedStat(TagKey key, String value, MeasureLong ml, int i) {
        recordTaggedStat(key, value, ml, new Long(i));
    }

    public static void recordTaggedStat(TagKey key, String value, MeasureLong ml, Long l) {
        Scope ss = tagger.withTagContext(tagger.emptyBuilder()
                                                .put(key, TagValue.create(value))
                                                .build());
        statsRecorder.newMeasureMap().put(ml, l).record();
        ss.close();
    }

    public static void recordTaggedStat(TagKey key, String value, MeasureDouble md, Double d) {
        Scope ss = tagger.withTagContext(tagger.emptyBuilder()
                                               .put(key, TagValue.create(value))
                                               .build());
        statsRecorder.newMeasureMap().put(md, d).record();
        ss.close();
    }

    public static void recordStatWithTags(MeasureLong ml, long l, TagKeyPair ...pairs) {
        TagContextBuilder tb = tagger.emptyBuilder();
        for (TagKeyPair kvp : pairs) {
            tb.put(kvp.key, TagValue.create(kvp.value));
        }

        TagContext tctx = tb.build();
        Scope ss = tagger.withTagContext(tctx);
        statsRecorder.newMeasureMap().put(ml, l).record();
        ss.close();
    }

    // RoundtripTrackingSpan records both the metric latency in
    // milliseconds, and the span created by tracing the calling function.
    public static class RoundtripTrackingSpan implements AutoCloseable {
        private Span span;
        private Scope spanScope;
        private long startTimeNs;
        private String method;
        private boolean closed;

        public RoundtripTrackingSpan(String name, String method) {
            this.startTimeNs = System.nanoTime();
            this.spanScope = tracer.spanBuilder(name).startScopedSpan();
            this.span = tracer.getCurrentSpan();
            this.method = method;
        }

        public void end() {
            if (this.closed)
                return;

            long totalTimeNs = System.nanoTime() - this.startTimeNs;
            double timeSpentMs = (new Double(totalTimeNs))/1e6;
            recordTaggedStat(keyMethod, this.method, mLatencyMs, timeSpentMs);
            this.closed = true;
            this.spanScope.close();
        }

        @Override
        public void close() {
            this.end();
        }
    }

    public static RoundtripTrackingSpan createRoundtripTrackingSpan(String spanName, String method) {
        return new RoundtripTrackingSpan(spanName, method);
    }

    public static class TagKeyPair {
        private TagKey key;
        private String value;

        public TagKeyPair(TagKey key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public static TagKeyPair tagKeyPair(TagKey key, String value) {
        return new TagKeyPair(key, value);
    }

    public static void registerAllViews() {
        Aggregation defaultBytesDistribution = Distribution.create(BucketBoundaries.create(
                    Arrays.asList(
                        // [0, 1KB,  2KB,    4KB,    16KB,    64KB,    256KB,    1MB,       4MB,       16MB,       64MB,       256MB,       1GB,          2GB,          4GB]
                        0.0, 1024.0, 2048.0, 4096.0, 16384.0, 65536.0, 262144.0, 1048576.0, 4194304.0, 16777216.0, 67108864.0, 268435456.0, 1073741824.0, 2147483648.0, 4294967296.0)
                    ));

        Aggregation defaultMillisecondsDistribution = Distribution.create(BucketBoundaries.create(
                    Arrays.asList(
                        // [0ms, 0.001ms, 0.005ms, 0.01ms, 0.05ms, 0.1ms, 0.5ms, 1ms, 1.5ms, 2ms, 2.5ms, 5ms, 10ms, 25ms, 50ms, 100ms, 200ms, 400ms, 600ms, 800ms, 1s, 1.5s, 2s, 2.5s, 5s, 10s, 20s, 40s, 100s, 200s, 500s]
                        0.0, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 1.5, 2.0, 2.5, 5.0, 10.0, 25.0, 50.0, 100.0, 200.0, 400.0, 600.0, 800.0, 1000.0, 1500.0, 2000.0, 2500.0, 5000.0, 10000.0, 20000.0, 40000.0, 100000.0, 200000.0, 500000.0)
                    ));

        Aggregation countAggregation = Aggregation.Count.create();
        List<TagKey> noKeys = new ArrayList<TagKey>();

        View[] views = new View[]{
            View.create(
                    Name.create("spymemcached/client/latency"),
                    "The distribution of the latencies of various calls in milliseconds",
                    mLatencyMs,
                    defaultMillisecondsDistribution,
                    Collections.unmodifiableList(Arrays.asList(keyMethod, keyPhase, keyReason, keyType))),

            View.create(
                    Name.create("spymemcached/client/calls"),
                    "The number of various calls of methods",
                    mCalls,
                    countAggregation,
                    Collections.unmodifiableList(Arrays.asList(keyMethod, keyPhase, keyReason, keyType))),

            View.create(
                    Name.create("spymemcached/client/errors"),
                    "The number of errors encountered",
                    mErrors,
                    countAggregation,
                    Collections.unmodifiableList(Arrays.asList(keyMethod, keyPhase, keyReason, keyType))),

            View.create(
                    Name.create("spymemcached/client/key_length"),
                    "The distribution of lengths of keys",
                    mKeyLength,
                    defaultBytesDistribution,
                    Collections.unmodifiableList(Arrays.asList(keyMethod, keyPhase, keyReason, keyType))),

            View.create(
                    Name.create("spymemcached/client/value_length"),
                    "The distribution of lengths of values",
                    mValueLength,
                    defaultBytesDistribution,
                    Collections.unmodifiableList(Arrays.asList(keyMethod, keyPhase, keyReason, keyType))),

            View.create(
                    Name.create("spymemcached/client/cache_hits"),
                    "The number of cache hits",
                    mCacheHits,
                    countAggregation,
                    Collections.unmodifiableList(Arrays.asList(keyMethod))),

            View.create(
                    Name.create("spymemcached/client/cache_misses"),
                    "The number of cache misses",
                    mCacheMisses,
                    countAggregation,
                    Collections.unmodifiableList(Arrays.asList(keyMethod))),
        };

        ViewManager vmgr = Stats.getViewManager();

        for (View v : views) {
            vmgr.registerView(v);
        }
    }
}
