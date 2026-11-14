package cljl;

import cljl.lang.PersistentList;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ClojureLite CLI & Interactive REPL Entry Point.
 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private Main() {}

    public static void main(String[] args) {
        RT.ensureInit();

        if (args.length == 0) {
            runRepl();
            return;
        }

        String first = args[0];
        if ("-h".equals(first) || "--help".equals(first)) {
            printHelp();
            return;
        }
        if ("-v".equals(first) || "--version".equals(first)) {
            System.out.println("ClojureLite " + VERSION + " (Java " + System.getProperty("java.version") + ")");
            return;
        }
        if ("-e".equals(first) || "--eval".equals(first)) {
            if (args.length < 2) {
                System.err.println("Error: " + first + " requires an expression argument");
                System.exit(1);
            }
            if (args.length > 2) {
                RT.COMMAND_LINE_ARGS.bindRoot(PersistentList.createFromArraySlice(args, 2));
            }
            evalAndPrint(args[1], "<eval>");
            return;
        }
        if ("-".equals(first)) {
            if (args.length > 1) {
                RT.COMMAND_LINE_ARGS.bindRoot(PersistentList.createFromArraySlice(args, 1));
            }
            runStdin();
            return;
        }

        // Script file execution
        Path scriptPath = Path.of(first);
        if (!Files.exists(scriptPath)) {
            System.err.println("Error: File not found: " + first);
            System.exit(1);
        }
        if (args.length > 1) {
            RT.COMMAND_LINE_ARGS.bindRoot(PersistentList.createFromArraySlice(args, 1));
        }
        try {
            String src = Files.readString(scriptPath, StandardCharsets.UTF_8);
            Compiler.load(src, first);
        } catch (Throwable t) {
            printError(t);
            System.exit(1);
        }
    }

    private static void evalAndPrint(String src, String filename) {
        try {
            new Reader(src, filename).consumeForms(form -> {
                Object res = Compiler.eval(form);
                if (res != null) {
                    System.out.println(RT.printString(res, true));
                }
            });
        } catch (Throwable t) {
            printError(t);
            System.exit(1);
        }
    }

    private static void runStdin() {
        try {
            Compiler.load(new InputStreamReader(System.in, StandardCharsets.UTF_8), "<stdin>");
        } catch (Throwable t) {
            printError(t);
            System.exit(1);
        }
    }

    private static void runRepl() {
        Var v1 = RT.STAR_1;
        Var v2 = RT.STAR_2;
        Var v3 = RT.STAR_3;
        Var ve = RT.STAR_E;

        System.out.println("ClojureLite " + VERSION + " (Java " + System.getProperty("java.version") + ")");
        System.out.println("Type :quit or EOF to exit.");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buf = new StringBuilder();

        while (true) {
            try {
                if (buf.isEmpty()) {
                    System.out.print(RT.currentNS().name.name + "=> ");
                } else {
                    System.out.print("...=> ");
                }
                System.out.flush();

                String line = reader.readLine();
                if (line == null) {
                    if (!buf.isEmpty()) {
                        processReplBuffer(buf.toString(), v1, v2, v3, ve);
                    }
                    System.out.println();
                    break;
                }

                String trimmed = line.trim();
                if (buf.isEmpty() && (":quit".equals(trimmed) || ":exit".equals(trimmed))) {
                    break;
                }
                if (buf.isEmpty() && trimmed.isEmpty()) {
                    continue;
                }

                buf.append(line).append('\n');
                if (isBalanced(buf)) {
                    String code = buf.toString();
                    buf.setLength(0);
                    processReplBuffer(code, v1, v2, v3, ve);
                }
            } catch (Throwable t) {
                buf.setLength(0);
                ve.bindRoot(t);
                printError(t);
            }
        }
    }

    private static void processReplBuffer(String code, Var v1, Var v2, Var v3, Var ve) {
        try {
            new Reader(code, "<repl>").consumeForms(form -> {
                Object res = Compiler.eval(form);
                v3.bindRoot(v2.get());
                v2.bindRoot(v1.get());
                v1.bindRoot(res);
                System.out.println(RT.printString(res, true));
            });
        } catch (Throwable t) {
            ve.bindRoot(t);
            printError(t);
        }
    }

    private static boolean isBalanced(CharSequence s) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        boolean inComment = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inComment) {
                if (c == '\n') inComment = false;
                continue;
            }
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == ';') {
                inComment = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
        }
        return !inString && depth <= 0;
    }

    private static void printError(Throwable t) {
        System.err.println("Execution error: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()));
        if (t.getCause() != null) {
            System.err.println("  Caused by: " + t.getCause());
        }
    }

    private static void printHelp() {
        System.out.println("ClojureLite " + VERSION + " - High-performance, fast-startup Clojure in Java");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  cljl                  Start interactive REPL");
        System.out.println("  cljl -e EXPR [ARGS]   Evaluate expression(s) and print non-nil results");
        System.out.println("  cljl FILE [ARGS]      Execute a Clojure script file");
        System.out.println("  cljl - [ARGS]         Read and execute script from stdin");
        System.out.println("  cljl -v, --version    Show version information");
        System.out.println("  cljl -h, --help       Show this help message");
    }
}
