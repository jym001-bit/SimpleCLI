package com.paicli.llm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 把 JSON 树直接写进 HTTP 输出流，不先拼成一整个字符串再转字节。
 *
 * <p>长上下文时整段对话历史要随每轮请求发出去：实测约 1M token 的对话加 3 张截图，
 * “toString 再转字节”每次分配约 90MB（字符串扩容加整串 UTF-16，再加 UTF-8 字节数组），
 * 在 G1 里都是大对象；直接写出只分配约 5MB。
 *
 * <p>先往计数流里写一遍拿到准确长度，照常带 Content-Length，不改成分块传输，
 * 免得个别网关不接受；重试时 OkHttp 会再次调用 {@link #writeTo}，每次都从树重新写出。
 */
final class JsonRequestBody extends RequestBody {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    // 按字节写出时 Jackson 默认把 emoji 这类 4 字节字符写成 \uD83E\uDDF9 转义；打开这项后原样写出，
    // 和原来 toString 的结果逐字节一致
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .build());

    private final JsonNode body;
    private long contentLength = -1;

    JsonRequestBody(JsonNode body) {
        this.body = body;
    }

    @Override
    public MediaType contentType() {
        return JSON;
    }

    @Override
    public synchronized long contentLength() throws IOException {
        if (contentLength < 0) {
            CountingOutputStream counter = new CountingOutputStream();
            write(counter);
            contentLength = counter.count;
        }
        return contentLength;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        write(sink.outputStream());
    }

    private void write(OutputStream out) throws IOException {
        // 不能让 Jackson 关掉 OkHttp 的输出流，否则请求体后面就写不进去了
        try (JsonGenerator generator = MAPPER.getFactory().createGenerator(out)
                .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            MAPPER.writeTree(generator, body);
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
