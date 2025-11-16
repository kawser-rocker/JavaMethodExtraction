package com.methodextractor;

import CSharpParser.CSharpLexer;
import CSharpParser.CSharpParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ExtractCSharpApiCalls {

    public static void main(String[] args) {
        try {
            // Resolve input file
            Path file;
            if (args.length > 0) {
                file = Paths.get(args[0]);
            } else {
                Path preferred = Paths.get("testfiles/2.cs");
                if (Files.exists(preferred)) file = preferred;
                else {
                    Path alt = Paths.get("2.cs");
                    if (Files.exists(alt)) file = alt;
                    else file = Paths.get("/mnt/data/2.cs"); // last fallback
                }
            }

            // Build ANTLR pipeline
            CharStream input = CharStreams.fromPath(file);
            CSharpLexer lexer = new CSharpLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            CSharpParser parser = new CSharpParser(tokens);

            // Parse the compilation unit
            ParseTree tree = parser.compilation_unit();

            // Walk with your extractor
            CSharpApiExtractor extractor = new CSharpApiExtractor();
            extractor.setTokens(tokens);                      // <-- ensure token post-pass has tokens
            ParseTreeWalker.DEFAULT.walk(extractor, tree);

            // Token post-pass (indexers, static calls, etc.)
            extractor.runTokenPostPass();

            // Print results
            System.out.println("== Fully Qualified Calls ==");
            List<String> calls = extractor.getResults();
            for (String c : calls) {
                System.out.println(c);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("ERROR: " + e.getMessage());
        }
    }
}
