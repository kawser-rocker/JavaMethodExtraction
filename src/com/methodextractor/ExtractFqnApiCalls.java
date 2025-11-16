package com.methodextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

// Generated classes live in the JavaParser package:
import JavaParser.JavaLexer;
import JavaParser.JavaParser;

public class ExtractFqnApiCalls {

    // Set your input file here
    private static final Path SOURCE = Paths.get("testfiles/2.java");

    public static void main(String[] args) {
        try {
            if (!Files.isRegularFile(SOURCE)) {
                System.err.println("File not found: " + SOURCE.toAbsolutePath());
                return;
            }

            List<String> calls = extract(SOURCE);

            System.out.println("== Fully Qualified Calls ==");
            for (String call : calls) {
                System.out.println(call);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Extract from a file path (used by main). */
    public static List<String> extract(Path javaFile) throws IOException {
        String code = Files.readString(javaFile, StandardCharsets.UTF_8);
        return extract(code);
    }

    /** Extract from a source string (handy for unit tests). */
    public static List<String> extract(String javaSource) {
        CharStream input = CharStreams.fromString(javaSource);
        JavaLexer lexer = new JavaLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        JavaParser parser = new JavaParser(tokens);

        // Fail-fast error strategy is convenient in IDEs
        parser.setErrorHandler(new BailErrorStrategy());

        ParseTree tree = parser.compilationUnit();

        FqnApiExtractor listener = new FqnApiExtractor();
        ParseTreeWalker.DEFAULT.walk(listener, tree);

        // Preserve encounter order while de-duplicating
        return new ArrayList<>(new LinkedHashSet<>(listener.getResults()));
    }
}
