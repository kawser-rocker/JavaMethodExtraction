package com.methodextractor.JavaExtractor;
import JavaParser.JavaLexer;
import JavaParser.JavaParser;
import com.methodextractor.FqnApiExtractor; // Java Parser Invocator
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

public class JavaFQNAPIExtractor {
    public static void main(String[] args) {
        // Root directory that contains all Java projects
        Path rootDir = Paths.get("D:\\XAPIRec_Data\\Data\\Java"); // CHANGE THIS PATH
        Path apiListsDir = Paths.get("APIListsCol\\Java");

        if (!Files.isDirectory(rootDir)) {
            System.err.println("Invalid root directory!");
            return;
        }

        try (DirectoryStream<Path> projects = Files.newDirectoryStream(rootDir)) {

            for (Path project : projects) {
                if (Files.isDirectory(project)) {
                    System.out.println("\n📁 Project: " + project.getFileName());
                    // list of API Calls
                    ArrayList<String> apiCalls = new ArrayList<>();

                    // find the list of files
                    ArrayList<Path> javaFiles = listJavaFiles(project);
                    for(Path file: javaFiles){
                        /// function call
                        apiCalls.addAll(getAPICalls(file));
                    }
//                    System.out.println("== Fully Qualified Calls ==");
//                    for (String call : apiCalls) {
//                        System.out.println(call);
//                    }
                    writeToFile(apiCalls, project.getFileName().toString(), apiListsDir);
                }
                //break;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<Path> listJavaFiles(Path projectDir) {
        ArrayList<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(javaFiles::add); // absolute paths
        } catch (IOException e) {
            System.err.println("Error scanning project: " + projectDir);
            e.printStackTrace();
        }
        return javaFiles;
    }

    private static ArrayList<String> getAPICalls(Path fileName){
        ArrayList<String> apiLists = new ArrayList<String>();
        try {
            if (!Files.isRegularFile(fileName)) {
                System.err.println("File not found: " + fileName.toAbsolutePath());
                return null;
            }

            apiLists.addAll(extract(fileName));

        } catch (Exception e) {
            e.printStackTrace();
        }
        return apiLists;
    }

    /** Extract from a file path (used by main). */
    private static ArrayList<String> extract(Path javaFile) throws IOException {
        String code = Files.readString(javaFile, StandardCharsets.UTF_8);
        return extract(code);
    }

    /** Extract from a source string (handy for unit tests). */
    private static ArrayList<String> extract(String javaSource) {
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

    private static void writeToFile(ArrayList<String> apiLists, String projectName, Path apiListsDir){
        try {
            Files.createDirectories(apiListsDir);
            Path outputProjPath = apiListsDir.resolve(projectName + ".txt");

            try (BufferedWriter writer = Files.newBufferedWriter(
                    outputProjPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )){
                for (String apicall : apiLists){
                    // Skip lines containing .<init>
                    if (apicall.contains(".<init>")) {
                        continue;
                    }
                    writer.write(apicall);
                    writer.newLine();
                }

            } catch (IOException ex){
                ex.printStackTrace();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
