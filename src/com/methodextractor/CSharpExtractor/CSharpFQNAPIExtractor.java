package com.methodextractor.CSharpExtractor;
import CSharpParser.CSharpLexer;
import CSharpParser.CSharpParser;
import JavaParser.JavaLexer;
import JavaParser.JavaParser;
import com.methodextractor.CSharpApiExtractor;
import com.methodextractor.FqnApiExtractor;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

public class CSharpFQNAPIExtractor {
    public static void main(String[] args){

        // Root directory that contains all Java projects
        Path rootDir = Paths.get("D:\\XAPIRec_Data\\Data\\C#"); // CHANGE THIS PATH
        Path apiListsDir = Paths.get("APIListsCol\\CSharp");

        if (!Files.isDirectory(rootDir)) {
            System.err.println("Invalid root directory!");
            return;
        }

        try (DirectoryStream<Path> projects = Files.newDirectoryStream(rootDir)) {
            Files.createDirectories(apiListsDir);
            for (Path project : projects) {
                if (Files.isDirectory(project)) {
                    String projectName = project.getFileName().toString();
                    Path outputFile = apiListsDir.resolve(projectName + ".txt");

                    if (Files.exists(outputFile)) {
                        System.out.println("Skipping (already processed): " + projectName);
                        continue;
                    }
                    System.out.println("\n📁 Project: " + project.getFileName());
                    // list of API Calls
                    ArrayList<String> apiCalls = new ArrayList<>();

                    // find the list of files
                    ArrayList<Path> csFiles = listCSharpFiles(project);
                    for(Path file: csFiles){
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

    private static ArrayList<Path> listCSharpFiles(Path projectDir) {
        ArrayList<Path> csFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".cs"))
                    .forEach(csFiles::add); // absolute paths
        } catch (IOException e) {
            System.err.println("Error scanning project: " + projectDir);
            e.printStackTrace();
        }
        return csFiles;
    }

    private static ArrayList<String> getAPICalls(Path fileName){
        ArrayList<String> apiLists = new ArrayList<>();
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

//    /** Extract from a file path (used by main). */
//    private static ArrayList<String> extract(Path csFile) throws IOException {
//        String code = Files.readString(csFile, StandardCharsets.UTF_8);
//        return extract(code);
//    }

    /** Extract from a source string (handy for unit tests). */
    private static ArrayList<String> extract(Path fileName) throws IOException{

        // Build ANTLR pipeline
        CharStream input = CharStreams.fromPath(fileName);
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

        // Preserve encounter order while de-duplicating
        return new ArrayList<>(new LinkedHashSet<>(extractor.getResults()));
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
                    if (apicall.contains(".ctor")) {
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
