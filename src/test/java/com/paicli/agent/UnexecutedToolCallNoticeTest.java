package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型在正文里写了工具调用却没被执行时，用户必须看到提示，不能把调用文字当成操作已完成。 */
class UnexecutedToolCallNoticeTest {

    private static final String DSML_BLOCK = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="list_dir">
            <｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""";

    @TempDir
    Path tempDir;

    private String oldMemoryDir;

    @BeforeEach
    void isolateMemory() {
        oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
    }

    @AfterEach
    void restoreMemory() {
        if (oldMemoryDir == null) {
            System.clearProperty("paicli.memory.dir");
        } else {
            System.setProperty("paicli.memory.dir", oldMemoryDir);
        }
    }

    @Test
    void agentAppendsNoticeWhenFinalAnswerContainsUnexecutedCall() {
        FlaggingClient llm = new FlaggingClient("我来列一下目录：\n" + DSML_BLOCK);
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("进入 demo 目录看看");

        assertTrue(result.contains("没有被执行"), result);
    }

    @Test
    void ordinaryAnswerHasNoNotice() {
        FlaggingClient llm = new FlaggingClient("demo 目录里只有 Hello.java");
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("demo 目录里有什么");

        assertFalse(result.contains("没有被执行"), result);
    }

    /** 不流式输出、按 DSML 标记识别残留调用的最小客户端。 */
    private static final class FlaggingClient implements LlmClient {
        private final String answer;

        private FlaggingClient(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", answer, null, 10, 2);
        }

        @Override
        public boolean looksLikeUnexecutedToolCall(String content) {
            return content != null && content.contains("｜｜DSML｜｜");
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
