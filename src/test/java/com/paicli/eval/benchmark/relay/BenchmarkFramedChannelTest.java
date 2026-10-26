package com.paicli.eval.benchmark.relay;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkFramedChannelTest {
    @Test
    void readsFragmentedUnicodeFrameAndCleanEof() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BenchmarkFramedChannel writer = new BenchmarkFramedChannel(InputStream.nullInputStream(), bytes);
        writer.write(complete("完成\n第二行：你好 👋"));

        BenchmarkFramedChannel reader = new BenchmarkFramedChannel(
                new ChunkedInputStream(bytes.toByteArray(), 1), OutputStreamSink.INSTANCE);
        BenchmarkRelayProtocol.WorkerComplete decoded = assertInstanceOf(
                BenchmarkRelayProtocol.WorkerComplete.class, reader.read());
        assertEquals("完成\n第二行：你好 👋", decoded.answer());
        assertNull(reader.read());
    }

    @Test
    void readsConsecutiveFramesWithoutConsumingBoundary() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BenchmarkFramedChannel writer = new BenchmarkFramedChannel(InputStream.nullInputStream(), bytes);
        writer.write(ready());
        writer.write(complete("ok"));

        BenchmarkFramedChannel reader = new BenchmarkFramedChannel(
                new ChunkedInputStream(bytes.toByteArray(), 3), OutputStreamSink.INSTANCE);
        assertInstanceOf(BenchmarkRelayProtocol.WorkerReady.class, reader.read());
        assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, reader.read());
        assertNull(reader.read());
    }

    @Test
    void rejectsBadMagicNegativeOversizedAndPartialFrames() throws Exception {
        assertThrows(IOException.class, () -> channel(frame(0x11111111, 0, new byte[0])).read());
        assertThrows(IOException.class, () -> channel(frame(BenchmarkFramedChannel.MAGIC, -1, new byte[0])).read());

        byte[] oversizedHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(BenchmarkFramedChannel.MAGIC).putInt(33).array();
        BenchmarkFramedChannel small = new BenchmarkFramedChannel(
                new ByteArrayInputStream(oversizedHeader), OutputStreamSink.INSTANCE, 32, 1024);
        assertThrows(IOException.class, small::read);

        assertThrows(EOFException.class, () -> channel(new byte[] {0x50, 0x41}).read());
        assertThrows(EOFException.class, () -> channel(frame(
                BenchmarkFramedChannel.MAGIC, 5, new byte[] {1, 2})).read());
    }

    @Test
    void enforcesPerFrameAndCumulativeSessionLimits() throws Exception {
        byte[] encoded = BenchmarkRelayProtocol.encode(ready());
        BenchmarkFramedChannel tooSmall = new BenchmarkFramedChannel(
                InputStream.nullInputStream(), new ByteArrayOutputStream(), encoded.length - 1, 4096);
        assertThrows(IOException.class, () -> tooSmall.write(ready()));

        long oneFrame = 8L + encoded.length;
        BenchmarkFramedChannel cumulative = new BenchmarkFramedChannel(
                InputStream.nullInputStream(), new ByteArrayOutputStream(), 1024 * 1024, oneFrame);
        cumulative.write(ready());
        assertThrows(IOException.class, () -> cumulative.write(ready()));
    }

    @Test
    void concurrentWritesRemainWholeFrames() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BenchmarkFramedChannel writer = new BenchmarkFramedChannel(InputStream.nullInputStream(), bytes);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int value = i;
                writes.add(pool.submit(() -> {
                    try {
                        writer.write(complete("answer-" + value));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            pool.shutdownNow();
        }

        BenchmarkFramedChannel reader = new BenchmarkFramedChannel(
                new ByteArrayInputStream(bytes.toByteArray()), OutputStreamSink.INSTANCE);
        List<String> answers = new ArrayList<>();
        BenchmarkRelayProtocol.Frame frame;
        while ((frame = reader.read()) != null) {
            answers.add(assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, frame).answer());
        }
        assertEquals(24, answers.size());
        for (int i = 0; i < 24; i++) {
            assertTrue(answers.contains("answer-" + i));
        }
    }

    @Test
    void rejectsStrictJsonViolationsInsideValidBinaryFrame() throws Exception {
        String valid = new String(BenchmarkRelayProtocol.encode(ready()), StandardCharsets.UTF_8);
        String protocolVersion = "\"protocolVersion\":" + BenchmarkRelayProtocol.VERSION;
        assertInvalidJson(valid.replaceFirst("\\{", "{\"unknown\":true,"));
        assertInvalidJson(valid.replaceFirst(protocolVersion,
                protocolVersion + "," + protocolVersion));
        assertInvalidJson(valid + " {} ");
        assertInvalidJson(valid.replace(protocolVersion, "\"protocolVersion\":99"));
        assertInvalidJson(valid.replace("\"type\":\"WORKER_READY\"", "\"type\":\"FUTURE_FRAME\""));
    }

    private static void assertInvalidJson(String json) {
        assertThrows(IOException.class, () -> channel(frame(
                BenchmarkFramedChannel.MAGIC,
                json.getBytes(StandardCharsets.UTF_8).length,
                json.getBytes(StandardCharsets.UTF_8))).read());
    }

    private static BenchmarkFramedChannel channel(byte[] bytes) {
        return new BenchmarkFramedChannel(new ByteArrayInputStream(bytes), OutputStreamSink.INSTANCE);
    }

    private static byte[] frame(int magic, int declaredLength, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(magic).putInt(declaredLength).put(payload);
        return buffer.array();
    }

    private static BenchmarkRelayProtocol.WorkerReady ready() {
        return new BenchmarkRelayProtocol.WorkerReady(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0),
                new BenchmarkRelayProtocol.Capabilities(
                        true, true, true, true, false, "automatic-prefix-cache", 1_000_000));
    }

    private static BenchmarkRelayProtocol.WorkerComplete complete(String answer) {
        return new BenchmarkRelayProtocol.WorkerComplete(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0), answer);
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] bytes;
        private final int chunk;
        private int offset;

        private ChunkedInputStream(byte[] bytes, int chunk) {
            this.bytes = bytes;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return offset >= bytes.length ? -1 : bytes[offset++] & 0xff;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset >= bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, chunk), bytes.length - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            return count;
        }
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int ignored) {
        }
    }
}
