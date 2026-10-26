package com.paicli.eval.benchmark.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Binary framing around strict relay JSON: 4-byte magic, 4-byte BE length, payload. */
public final class BenchmarkFramedChannel {
    public static final int MAGIC = 0x50414942; // "PAIB"
    public static final int MAX_FRAME_BYTES = 16 * 1_048_576;
    public static final long MAX_SESSION_BYTES = 256L * 1_048_576;

    private static final int HEADER_BYTES = 8;

    private final InputStream input;
    private final OutputStream output;
    private final int maxFrameBytes;
    private final long maxSessionBytes;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private long sessionBytes;

    public BenchmarkFramedChannel(InputStream input, OutputStream output) {
        this(input, output, MAX_FRAME_BYTES, MAX_SESSION_BYTES);
    }

    BenchmarkFramedChannel(InputStream input, OutputStream output, int maxFrameBytes, long maxSessionBytes) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        if (maxFrameBytes <= 0 || maxFrameBytes > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes out of range");
        }
        if (maxSessionBytes < HEADER_BYTES || maxSessionBytes > MAX_SESSION_BYTES) {
            throw new IllegalArgumentException("maxSessionBytes out of range");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.maxSessionBytes = maxSessionBytes;
    }

    /** Returns {@code null} only for a clean EOF before any byte of a new frame. */
    public BenchmarkRelayProtocol.Frame read() throws IOException {
        synchronized (readLock) {
            byte[] header = new byte[HEADER_BYTES];
            int first = input.read();
            if (first < 0) {
                return null;
            }
            header[0] = (byte) first;
            readFully(input, header, 1, HEADER_BYTES - 1, "truncated relay frame header");
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            int magic = buffer.getInt();
            if (magic != MAGIC) {
                throw new IOException("invalid relay frame magic");
            }
            int length = buffer.getInt();
            if (length < 0) {
                throw new IOException("negative relay frame length");
            }
            if (length > maxFrameBytes) {
                throw new IOException("relay frame exceeds " + maxFrameBytes + " bytes");
            }
            reserve(HEADER_BYTES + (long) length);
            byte[] payload = new byte[length];
            readFully(input, payload, 0, length, "truncated relay frame payload");
            return BenchmarkRelayProtocol.decode(payload);
        }
    }

    /** Writes one frame atomically with respect to other channel writers. */
    public void write(BenchmarkRelayProtocol.Frame frame) throws IOException {
        byte[] payload = BenchmarkRelayProtocol.encode(frame);
        if (payload.length > maxFrameBytes) {
            throw new IOException("relay frame exceeds " + maxFrameBytes + " bytes");
        }
        synchronized (writeLock) {
            reserve(HEADER_BYTES + (long) payload.length);
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putInt(MAGIC).putInt(payload.length).array();
            output.write(header);
            output.write(payload);
            output.flush();
        }
    }

    private synchronized void reserve(long bytes) throws IOException {
        if (bytes > maxSessionBytes - sessionBytes) {
            throw new IOException("relay session exceeds " + maxSessionBytes + " bytes");
        }
        sessionBytes += bytes;
    }

    private static void readFully(InputStream in, byte[] target, int offset, int length, String message)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(target, offset + read, length - read);
            if (count < 0) {
                throw new EOFException(message);
            }
            if (count == 0) {
                int one = in.read();
                if (one < 0) {
                    throw new EOFException(message);
                }
                target[offset + read] = (byte) one;
                read++;
            } else {
                read += count;
            }
        }
    }
}
