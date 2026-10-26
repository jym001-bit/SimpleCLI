import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.terminal.spi.SystemStream;

/** Run with: java -cp target/paicli-1.0-SNAPSHOT.jar tools/TerminalProbe.java */
class TerminalProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("TERM: " + System.getenv("TERM"));
        System.out.println("TERMINAL_EMULATOR: " + System.getenv("TERMINAL_EMULATOR"));
        System.out.println("TERM_PROGRAM: " + System.getenv("TERM_PROGRAM"));
        for (String name : new String[]{"jni"}) {
            try {
                var provider = TerminalProvider.load(name);
                System.out.println(name + " stdin=" + provider.isSystemStream(SystemStream.Input)
                        + " stdout=" + provider.isSystemStream(SystemStream.Output));
            } catch (Throwable error) {
                error.printStackTrace(System.out);
            }
        }
        try (var terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            System.out.println("Terminal: " + terminal.getClass().getName());
            System.out.println("Type: " + terminal.getType() + " size=" + terminal.getSize());
            System.out.println("Fixed status dock: "
                    + com.paicli.render.inline.TerminalCapabilities.supportsScrollRegion(terminal));
            System.out.println("LineReader status footer: "
                    + com.paicli.render.inline.TerminalCapabilities.supportsLineReaderFooter(terminal));
            if (args.length > 0 && (args[0].equals("--menu") || args[0].equals("--thinking"))) {
                var type = Class.forName("com.paicli.cli.SlashMenuLineReader");
                var constructor = type.getDeclaredConstructor(org.jline.terminal.Terminal.class);
                constructor.setAccessible(true);
                var reader = (org.jline.reader.LineReader) constructor.newInstance(terminal);
                reader.option(org.jline.reader.LineReader.Option.ERASE_LINE_ON_FINISH, true);
                var install = type.getDeclaredMethod("installSlashMenuBindings");
                install.setAccessible(true);
                install.invoke(reader);
                if (args[0].equals("--thinking")) {
                    try (var renderer = new com.paicli.render.inline.InlineRenderer(terminal)) {
                        renderer.bindLineReader(reader);
                        renderer.start();
                        renderer.updateStatus(com.paicli.render.StatusInfo.idle("layout-test", 200000L, false));
                        renderer.beginThinking("Thinking");
                        renderer.appendThinking("Reasoning preview");
                        renderer.toggleThinkingBlocks();
                        renderer.endThinking();
                        for (int row = 1; row <= 30; row++) {
                            renderer.stream().println("Transcript row " + row + " - must stay above the status area.");
                            renderer.updateStatus(com.paicli.render.StatusInfo.tokens("layout-test", 200000L,
                                    row * 10L, row * 10L, row, 0L, null, false, row * 100L, "running"));
                        }
                        renderer.updateStatus(com.paicli.render.StatusInfo.idle("layout-test", 200000L, false));
                        var bind = Class.forName("com.paicli.cli.Main").getDeclaredMethod("bindCtrlOToFoldableBlocks",
                                org.jline.reader.LineReader.class, com.paicli.render.inline.InlineRenderer.class);
                        bind.setAccessible(true);
                        bind.invoke(null, reader, renderer);
                        renderer.appendThinkingBlock("Reasoning detail line\n".repeat(40));
                        renderer.stream().println("ANSWER appears once. Ctrl+T opens/closes details; PgDn scrolls.");
                        reader.readLine("> ");
                    }
                    return;
                }
                System.out.println("Type / to test the menu. Submitted text is only echoed, never executed.");
                System.out.println("Submitted: " + reader.readLine("> "));
            }
        }
    }
}
