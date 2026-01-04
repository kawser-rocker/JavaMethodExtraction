package com.methodextractor.PythonExtractor;

import PythonParser.PythonLexer;
import PythonParser.PythonParser;
import com.methodextractor.PyFqnApiExtractor;
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
import java.util.List;
import java.util.stream.Stream;

public class PythonFQNAPIExtractor {
    public static void main(String[] args) {
        // Root directory that contains all Java projects
        Path rootDir = Paths.get("D:\\XAPIRec_Data\\Data\\Python"); // CHANGE THIS PATH
        Path apiListsDir = Paths.get("APIListsCol\\Python");

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
                    ArrayList<Path> pyFiles = listPythonFiles(project);
                    for(Path file: pyFiles){
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

    private static ArrayList<Path> listPythonFiles(Path projectDir) {
        ArrayList<Path> pyFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".py"))
                    .forEach(pyFiles::add); // absolute paths
        } catch (IOException e) {
            System.err.println("Error scanning project: " + projectDir);
            e.printStackTrace();
        }
        return pyFiles;
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

    public static ArrayList<String> extract(Path pyFile) throws IOException {
        String code = Files.readString(pyFile, StandardCharsets.UTF_8);
        return extract(code);
    }

    public static ArrayList<String> extract(String pythonSource) {
        CharStream input = CharStreams.fromString(pythonSource);
        PythonLexer lexer = new PythonLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PythonParser parser = new PythonParser(tokens);

        ParseTree tree = parser.file_input();

        PyFqnApiExtractor listener = new PyFqnApiExtractor();
        ParseTreeWalker.DEFAULT.walk(listener, tree);

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
                    if (apicall.contains("__init__") || apicall.contains("self.")) {
                        continue;
                    }
                    if(apicall.contains("builtins.")){
                        apicall = apicall.replace("builtins.","");
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
