package com.methodextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import PythonParser.PythonLexer;
import PythonParser.PythonParser;

public class ExtractPyFqnApiCalls {

    private static final Path SOURCE = Paths.get("testfiles/bubblesort.py");

    public static void main(String[] args) {
        try {

            // Uncomment to run the in-memory demo first:
            //testWithString();

            if (!Files.isRegularFile(SOURCE)) {
                System.err.println("File not found: " + SOURCE.toAbsolutePath());
                return;
            }
            List<String> calls = extract(SOURCE);

            System.out.println("== Fully Qualified API Calls ==");
            for (String call : calls) System.out.println(call);

        } catch (Exception e) {
            System.err.println("Error during extraction: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static List<String> extract(Path pyFile) throws IOException {
        String code = Files.readString(pyFile, StandardCharsets.UTF_8);
        return extract(code);
    }

    public static List<String> extract(String pythonSource) {
        CharStream input = CharStreams.fromString(pythonSource);
        PythonLexer lexer = new PythonLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PythonParser parser = new PythonParser(tokens);

        ParseTree tree = parser.file_input();

        PyFqnApiExtractor listener = new PyFqnApiExtractor();
        ParseTreeWalker.DEFAULT.walk(listener, tree);

        return new ArrayList<>(new LinkedHashSet<>(listener.getResults()));
    }

    // Utility method for testing with sample Python code
    public static void testWithString() {
        String sampleCode = """
            import os
            import json
            from collections import defaultdict
            import numpy as np
            from datetime import datetime

            # Basic function calls
            print("Hello World")
            len([1, 2, 3])
            max(1, 2, 3)

            # Module function calls
            os.path.join("/home", "user")
            json.dumps({"key": "value"})

            # Constructor calls and assignments
            my_dict = defaultdict(list)
            arr = np.array([1, 2, 3])
            now = datetime.now()

            # Method calls on objects
            data = {"test": "value"}
            result = data.get("test")
            keys = data.keys()

            # Attribute access
            path_sep = os.sep
            pi_value = np.pi

            # Chained calls
            path = os.path.dirname(os.path.abspath(__file__))

            # Indexing operations
            first_item = arr[0]
            dict_value = data["test"]

            # Arithmetic operations (magic methods)
            result = arr + np.array([4, 5, 6])
            total = sum([1, 2, 3])

            # String operations
            text = "hello world"
            upper_text = text.upper()
            split_text = text.split()

            # List operations
            numbers = [1, 2, 3]
            numbers.append(4)
            numbers.extend([5, 6])

            # File operations
            with open("file.txt", "r") as f:
                content = f.read()
                lines = content.splitlines()

            # Class definition (local - should be ignored)
            class MyClass:
                def __init__(self):
                    self.value = "test"

                def method(self):
                    return len(self.value)

            # Using local class
            obj = MyClass()
            obj_value = obj.method()

            # Decorator usage
            @property
            def decorated_method(self):
                return self.value

            @staticmethod
            def static_method():
                return "static"
            """;

        System.out.println("=== Testing Comprehensive Python Code ===");
        System.out.println(sampleCode);
        System.out.println("\n== Extracted Fully Qualified API Calls ==");

        List<String> calls = extract(sampleCode);
        if (calls.isEmpty()) {
            System.out.println("No API calls found.");
        } else {
            calls.forEach(System.out::println);
        }
        System.out.println("\nTotal API calls found: " + calls.size());
    }
}
