/*
 * Copyright (c) 2008-2024, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.util;

import com.hazelcast.client.map.helpers.AMapStore;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.nio.Bits;
import com.hazelcast.internal.nio.BufferObjectDataInput;
import com.hazelcast.internal.partition.InternalPartitionService;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.HeapData;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.jet.impl.JetServiceBackend;
import com.hazelcast.jet.impl.execution.MockSnapshotContext;
import com.hazelcast.jet.impl.util.AsyncSnapshotWriterImpl.CustomByteArrayOutputStream;
import com.hazelcast.jet.impl.util.AsyncSnapshotWriterImpl.SnapshotDataKey;
import com.hazelcast.jet.impl.util.AsyncSnapshotWriterImpl.SnapshotDataValueTerminator;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.impl.util.Util.uncheckCall;
import static java.util.Collections.nCopies;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.generate;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class AsyncSnapshotWriterImplTest extends JetTestSupport {

    private static final String ALWAYS_FAILING_MAP = "alwaysFailingMap";

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private NodeEngineImpl nodeEngine;
    private AsyncSnapshotWriterImpl writer;
    private IMap<SnapshotDataKey, byte[]> map;
    private InternalSerializationService serializationService;
    private InternalPartitionService partitionService;
    private MockSnapshotContext snapshotContext;

    @Before
    public void before() {
        Config config = new Config();
        config.getMapConfig(ALWAYS_FAILING_MAP)
              .getMapStoreConfig()
              .setEnabled(true)
              .setImplementation(new AlwaysFailingMapStore());
        config.getJetConfig().setEnabled(true);

        HazelcastInstance instance = createHazelcastInstance(config);
        nodeEngine = Util.getNodeEngine(instance);
        serializationService = Util.getSerializationService(instance);
        partitionService = nodeEngine.getPartitionService();
        snapshotContext = new MockSnapshotContext();
        snapshotContext.setCurrentMapName("map1");
        snapshotContext.setCurrentSnapshotId(0L);
        writer = new AsyncSnapshotWriterImpl(128, nodeEngine, snapshotContext, "vertex", 0, 1,
                (InternalSerializationService) nodeEngine.getSerializationService());
        snapshotContext.setCurrentSnapshotId(1L); // simulates starting new snapshot
        map = instance.getMap("map1");
        assertTrue(writer.usableChunkCapacity > 0);
    }

    @After
    public void after() {
        assertTrue(writer.flushAndResetMap());
        assertTrueEventually(() -> assertFalse(uncheckCall(() -> writer.hasPendingAsyncOps())));
        assertTrue(writer.isEmpty());
    }

    @Test
    public void test_flushingAtEdgeCases() {
        for (int i = 64; i < 196; i++) {
            snapshotContext.setCurrentMapName(randomMapName());
            writer = new AsyncSnapshotWriterImpl(128, nodeEngine, snapshotContext, "vertex", 0, 1,
                    (InternalSerializationService) nodeEngine.getSerializationService());
            try {
                assertTrue(writer.offer(entry(serialize("k"), serialize(String.join("", nCopies(i, "a"))))));
                assertTrue(writer.flushAndResetMap());
            } catch (Exception e) {
                throw new RuntimeException("error at i=" + i, e);
            }
        }
    }

    @Test
    public void when_writeOneKeyAndFlush_then_written() {
        // When
        Entry<Data, Data> entry = entry(serialize("k"), serialize("v"));
        assertTrue(writer.offer(entry));
        assertTrueAllTheTime(() -> assertTrue(map.isEmpty()), 1);
        assertFalse(writer.isEmpty());
        assertTrue(writer.flushAndResetMap());

        // Then
        assertTargetMapEntry("k", 0, serializedLength(entry));
        assertEquals(1, map.size());
    }

    @Test
    public void when_chunkSizeWouldExceedLimit_then_flushedAutomatically() {
        // When
        Entry<Data, Data> entry = entry(serialize("k"), serialize("v"));
        int entriesInChunk =
                (writer.usableChunkCapacity - writer.serializedByteArrayHeader.length) / serializedLength(entry);
        assertTrue("entriesInChunk=" + entriesInChunk, entriesInChunk > 1 && entriesInChunk < 10);

        for (int i = 0; i < entriesInChunk; i++) {
            assertTrue(writer.offer(entry));
        }
        assertTrueAllTheTime(() ->
                assertTrue(
                        map.entrySet().stream()
                           .map(Entry::toString)
                           .collect(joining(", ", "[", "]")),
                        map.isEmpty()), 1);
        // this entry will cause automatic flush
        assertTrue(writer.offer(entry));

        // Then
        assertTargetMapEntry("k", 0, serializedLength(entry) * entriesInChunk);
        assertFalse(writer.isEmpty());

        // When - try once more
        for (int i = 1; i < entriesInChunk; i++) {
            assertTrue(writer.offer(entry));
        }
        assertTrueAllTheTime(() -> assertEquals(1, map.size()), 1);
        // this entry will cause automatic flush
        assertTrue(writer.offer(entry));

        // Then
        assertTargetMapEntry("k", 1, serializedLength(entry) * entriesInChunk);
    }

    @Test
    public void when_twoPartitions_then_twoEntries() {
        // When
        Entry<Data, Data> entry1 = entry(serialize("k"), serialize("v"));
        Entry<Data, Data> entry2 = entry(serialize("kk"), serialize("vv"));
        assertTrue(writer.offer(entry1));
        assertTrue(writer.offer(entry2));
        assertTrue(writer.flushAndResetMap());

        // Then
        assertTargetMapEntry("k", 0, serializedLength(entry1));
        assertTargetMapEntry("kk", 1, serializedLength(entry2));
    }

    @Test
    public void when_singleLargeEntry_then_flushedImmediatelyAndDeserializesCorrectly() throws IOException {
        // When
        String key = "k";
        String value = generate(() -> "a").limit(128).collect(joining());
        Entry<Data, Data> entry = entry(serialize(key), serialize(value));
        assertTrue("entry not longer than usable chunk size", serializedLength(entry) > writer.usableChunkCapacity);
        assertTrue(writer.offer(entry));

        // Then
        assertTargetMapEntry(key, 0, serializedLength(entry));
        assertEquals(1, writer.getTotalChunks());
        assertEquals(1, writer.getTotalKeys());

        // Then2 - try to deserialize the entry
        int partitionKey = writer.partitionKey(partitionService.getPartitionId(key));
        byte[] data = map.get(new SnapshotDataKey(partitionKey, 1, "vertex", 0));
        assertEquals(data.length + Bits.INT_SIZE_IN_BYTES, writer.getTotalPayloadBytes());
        BufferObjectDataInput in = serializationService.createObjectDataInput(data);
        assertEquals(key, serializationService.readObject(in, true));
        assertEquals(value, serializationService.readObject(in, true));
        assertEquals(SnapshotDataValueTerminator.INSTANCE, serializationService.readObject(in, true));
    }

    @Test
    public void when_cannotAutoFlush_then_offerReturnsFalse() {
        // When
        // artificially increase number of async ops so that the writer cannot proceed
        writer.numConcurrentAsyncOps.set(JetServiceBackend.MAX_PARALLEL_ASYNC_OPS);
        Entry<Data, Data> entry = entry(serialize("k"), serialize("v"));
        int entriesInChunk =
                (writer.usableChunkCapacity - writer.serializedByteArrayHeader.length) / serializedLength(entry);
        assertTrue("entriesInChunk=" + entriesInChunk, entriesInChunk > 1 && entriesInChunk < 10);
        for (int i = 0; i < entriesInChunk; i++) {
            assertTrue(writer.offer(entry));
        }

        // Then
        assertFalse("offer should not have succeeded, too many parallel operations", writer.offer(entry));

        writer.numConcurrentAsyncOps.set(0);
        assertTrue("offer should have succeeded", writer.offer(entry));
        assertTargetMapEntry("k", 0, serializedLength(entry) * entriesInChunk);
    }

    @Test
    public void when_cannotFlushRemaining_then_returnsFalse() {
        // When
        // artificially increase number of async ops so that the writer cannot proceed
        writer.numConcurrentAsyncOps.set(JetServiceBackend.MAX_PARALLEL_ASYNC_OPS);
        Entry<Data, Data> entry1 = entry(serialize("k"), serialize("v"));
        Entry<Data, Data> entry2 = entry(serialize("kk"), serialize("vv"));
        assertTrue(writer.offer(entry1));
        assertTrue(writer.offer(entry2));

        // Then
        assertTrueAllTheTime(() -> {
            assertFalse(writer.flushAndResetMap());
            assertTrue(map.isEmpty());
        }, 3);

        // When - release one parallel op - we should eventually flush all buffers, one by one
        writer.numConcurrentAsyncOps.decrementAndGet();

        // Then
        assertTrueEventually(() -> assertTrue(writer.flushAndResetMap()));
        assertTargetMapEntry("k", 0, serializedLength(entry1));
        assertTargetMapEntry("kk", 1, serializedLength(entry2));
    }

    @Test
    public void when_error_then_reported() {
        // When
        snapshotContext.setCurrentMapName(ALWAYS_FAILING_MAP);
        Entry<Data, Data> entry = entry(serialize("k"), serialize("v"));
        assertTrue(writer.offer(entry));
        assertTrue(writer.flushAndResetMap());

        // Then
        assertTrueEventually(() ->
                assertThat(String.valueOf(writer.getError())).contains("Always failing store"));
    }

    @Test
    public void test_serializeAndDeserialize() throws Exception {
        // This is the way we serialize and deserialize objects into the snapshot. We depend on some internals of IMDG:
        // - using the HeapData.toByteArray() from offset 4
        // - concatenate them into one array
        // - read that array with createObjectDataInput(byte[])
        // Purpose of this test is to check that they didn't change anything...

        Data serialized1 = serializationService.toData("foo");
        Data serialized2 = serializationService.toData("bar");
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Stream.of(serialized1, serialized2).forEach(serialized -> {
            Assert.assertTrue("unexpected class: " + serialized.getClass(), serialized instanceof HeapData);
            byte[] bytes = serialized.toByteArray();
            os.write(bytes, HeapData.TYPE_OFFSET, bytes.length - HeapData.TYPE_OFFSET);
        });

        BufferObjectDataInput in = serializationService.createObjectDataInput(os.toByteArray());
        Assert.assertEquals("foo", serializationService.readObject(in, true));
        Assert.assertEquals("bar", serializationService.readObject(in, true));
    }

    @Test
    public void when_noItemsAndNoCurrentMap_then_flushAndResetReturnsFalse() {
        snapshotContext.setCurrentMapName(null);
        assertFalse(writer.flushAndResetMap());
        snapshotContext.setCurrentMapName("map1");
    }

    private void assertTargetMapEntry(String key, int sequence, int entryLength) {
        int partitionKey = writer.partitionKey(partitionService.getPartitionId(key));
        SnapshotDataKey mapKey = new SnapshotDataKey(partitionKey, 1, "vertex", sequence);
        int entryLengthWithTerminator = entryLength + writer.valueTerminator.length;
        assertTrueEventually(() ->
                assertEquals(entryLengthWithTerminator, map.get(mapKey).length), 3);
    }

    private int serializedLength(Entry<Data, Data> entry) {
        return entry.getKey().totalSize() + entry.getValue().totalSize() - 8;
    }

    private Data serialize(String str) {
        return serializationService.toData(str);
    }

    /* ***********************************/
    /* CustomByteArrayOutputStream tests */
    /* ***********************************/

    @Test
    public void when_bufferExceeded_then_thrown() {
        // Given
        CustomByteArrayOutputStream os = new CustomByteArrayOutputStream(4);
        os.write(1);
        os.write(1);
        os.write(1);
        os.write(1);

        // Then
        exception.expect(RuntimeException.class);
        // When
        os.write(1);
    }

    static class AlwaysFailingMapStore extends AMapStore implements Serializable {

        @Override
        public void store(Object o, Object o2) {
            throw new RuntimeException("Always failing store");
        }
    }
}
