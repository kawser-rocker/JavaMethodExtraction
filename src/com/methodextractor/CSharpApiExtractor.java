package com.methodextractor;

import CSharpParser.CSharpParser;
import CSharpParser.CSharpParserBaseListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.*;

/**
 * Comprehensive C# API call extractor for legitimate method calls and constructors
 */
public class CSharpApiExtractor extends CSharpParserBaseListener {

    private static final Map<String, String> WELL_KNOWN_SIMPLE = new HashMap<>();
    static {
        // C# primitive types and aliases
        WELL_KNOWN_SIMPLE.put("int", "System.Int32");
        WELL_KNOWN_SIMPLE.put("string", "System.String");
        WELL_KNOWN_SIMPLE.put("bool", "System.Boolean");
        WELL_KNOWN_SIMPLE.put("double", "System.Double");
        WELL_KNOWN_SIMPLE.put("float", "System.Single");
        WELL_KNOWN_SIMPLE.put("decimal", "System.Decimal");
        WELL_KNOWN_SIMPLE.put("char", "System.Char");
        WELL_KNOWN_SIMPLE.put("byte", "System.Byte");
        WELL_KNOWN_SIMPLE.put("sbyte", "System.SByte");
        WELL_KNOWN_SIMPLE.put("short", "System.Int16");
        WELL_KNOWN_SIMPLE.put("ushort", "System.UInt16");
        WELL_KNOWN_SIMPLE.put("uint", "System.UInt32");
        WELL_KNOWN_SIMPLE.put("long", "System.Int64");
        WELL_KNOWN_SIMPLE.put("ulong", "System.UInt64");
        WELL_KNOWN_SIMPLE.put("object", "System.Object");
        WELL_KNOWN_SIMPLE.put("void", "System.Void");

        // Common .NET types
        WELL_KNOWN_SIMPLE.put("String", "System.String");
        WELL_KNOWN_SIMPLE.put("Object", "System.Object");
        WELL_KNOWN_SIMPLE.put("Console", "System.Console");
        WELL_KNOWN_SIMPLE.put("Math", "System.Math");
        WELL_KNOWN_SIMPLE.put("DateTime", "System.DateTime");
        WELL_KNOWN_SIMPLE.put("TimeSpan", "System.TimeSpan");
        WELL_KNOWN_SIMPLE.put("Guid", "System.Guid");
        WELL_KNOWN_SIMPLE.put("Task", "System.Threading.Tasks.Task");
        WELL_KNOWN_SIMPLE.put("StreamReader", "System.IO.StreamReader");
        WELL_KNOWN_SIMPLE.put("StreamWriter", "System.IO.StreamWriter");
        WELL_KNOWN_SIMPLE.put("List", "System.Collections.Generic.List");
        WELL_KNOWN_SIMPLE.put("Dictionary", "System.Collections.Generic.Dictionary");
        WELL_KNOWN_SIMPLE.put("HashSet", "System.Collections.Generic.HashSet");
        WELL_KNOWN_SIMPLE.put("Queue", "System.Collections.Generic.Queue");
        WELL_KNOWN_SIMPLE.put("Stack", "System.Collections.Generic.Stack");
        WELL_KNOWN_SIMPLE.put("ArrayList", "System.Collections.ArrayList");
        WELL_KNOWN_SIMPLE.put("Hashtable", "System.Collections.Hashtable");
        WELL_KNOWN_SIMPLE.put("File", "System.IO.File");
        WELL_KNOWN_SIMPLE.put("Directory", "System.IO.Directory");
        WELL_KNOWN_SIMPLE.put("Path", "System.IO.Path");
        WELL_KNOWN_SIMPLE.put("Thread", "System.Threading.Thread");
        WELL_KNOWN_SIMPLE.put("StringBuilder", "System.Text.StringBuilder");
        WELL_KNOWN_SIMPLE.put("Regex", "System.Text.RegularExpressions.Regex");
        WELL_KNOWN_SIMPLE.put("Enumerable", "System.Linq.Enumerable");
        WELL_KNOWN_SIMPLE.put("Queryable", "System.Linq.Queryable");
        WELL_KNOWN_SIMPLE.put("Action", "System.Action");
        WELL_KNOWN_SIMPLE.put("Func", "System.Func");
        WELL_KNOWN_SIMPLE.put("Predicate", "System.Predicate");
        WELL_KNOWN_SIMPLE.put("Convert", "System.Convert");
        WELL_KNOWN_SIMPLE.put("Environment", "System.Environment");
        WELL_KNOWN_SIMPLE.put("Type", "System.Type");
        WELL_KNOWN_SIMPLE.put("Exception", "System.Exception");
        WELL_KNOWN_SIMPLE.put("Random", "System.Random");
        WELL_KNOWN_SIMPLE.put("Array", "System.Array");
    }


    // --- Generic type parsing helpers ---
    private static class TypeInfo {
        final String erasure;
        final List<String> typeArgs;
        TypeInfo(String erasure, List<String> typeArgs) {
            this.erasure = erasure;
            this.typeArgs = typeArgs;
        }
    }

    private TypeInfo parseGenericType(String typeText) {
        if (typeText == null) return new TypeInfo(null, Collections.emptyList());
        int lt = typeText.indexOf('<');
        if (lt < 0) {
            return new TypeInfo(typeText, Collections.emptyList());
        }
        int gt = typeText.lastIndexOf('>');
        String erasure = typeText.substring(0, lt).trim();
        String inside = typeText.substring(lt + 1, gt);
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '<') { depth++; cur.append(c); }
            else if (c == '>') { depth--; cur.append(c); }
            else if (c == ',' && depth == 0) {
                args.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) args.add(cur.toString().trim());
        return new TypeInfo(erasure, args);
    }
    private static final Set<String> CS_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract","as","base","bool","break","byte","case","catch","char","checked","class","const",
            "continue","decimal","default","delegate","do","double","else","enum","event","explicit",
            "extern","false","finally","fixed","float","for","foreach","goto","if","implicit","in",
            "int","interface","internal","is","lock","long","namespace","new","null","object","operator",
            "out","override","params","private","protected","public","readonly","ref","return","sbyte",
            "sealed","short","sizeof","stackalloc","static","string","struct","switch","this","throw",
            "true","try","typeof","uint","ulong","unchecked","unsafe","ushort","using","virtual","void",
            "volatile","while","var","dynamic","partial","yield","await","async","get","set","add","remove"
    ));

    // Using directives and aliases
    private final List<String> usingNamespaces = new ArrayList<>();
    private final Map<String, String> usingAliasMap = new HashMap<>();
    private final Set<String> usingStaticTypes = new HashSet<>();

    // Variable type registry
    private final Map<String, String> globalVariableTypes = new HashMap<>();
    private final Map<String, String> fieldTypes = new HashMap<>();
    private final Map<String, String> parameterTypes = new HashMap<>();

    // Current scope stack for local variables
    private final Deque<Map<String, String>> scopes = new ArrayDeque<>();

    // Local project types to filter out
    private final Set<String> localTypes = new HashSet<>();

    // Extension method tracking
    private final Set<String> extensionMethodNamespaces = new HashSet<>();

    // Results
    private final LinkedHashSet<String> results = new LinkedHashSet<>();

    // Token stream
    private CommonTokenStream tokens;

    // Current context
    private String currentNamespace = "";
    private String currentClass = "";

    public CSharpApiExtractor() {
        pushScope();
        usingNamespaces.add("System");
        extensionMethodNamespaces.add("System.Linq");
    }

    private void pushScope() { scopes.push(new HashMap<>()); }
    private void popScope() { if (!scopes.isEmpty()) scopes.pop(); }

    private void declareVariable(String name, String typeName) {
        if (name == null || typeName == null) return;

        // Add to current scope
        if (!scopes.isEmpty()) {
            scopes.peek().put(name, typeName);
        }

        // Also add to global registry for cross-scope lookup
        globalVariableTypes.put(name, typeName);
    }

    private String lookupVariable(String name) {
        // First check current scope stack
        for (Map<String, String> scope : scopes) {
            if (scope.containsKey(name)) return scope.get(name);
        }

        // Then check global registry
        return globalVariableTypes.get(name);
    }

    public void setTokens(CommonTokenStream tokens) { this.tokens = tokens; }
    public List<String> getResults() { return new ArrayList<>(results); }

    // Parser callbacks for scope management
    @Override
    public void enterCompilation_unit(CSharpParser.Compilation_unitContext ctx) {
        pushScope();
    }

    @Override
    public void exitCompilation_unit(CSharpParser.Compilation_unitContext ctx) {
        popScope();
    }

    @Override
    public void exitUsing_directive(CSharpParser.Using_directiveContext ctx) {
        String text = ctx.getText();
        if (text == null) return;

        text = text.trim();
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1);
        if (!text.startsWith("using")) return;

        String rest = text.substring(5).trim();

        if (rest.startsWith("static")) {
            String target = rest.substring(6).trim();
            usingStaticTypes.add(target);
            return;
        }

        int eq = rest.indexOf('=');
        if (eq >= 0) {
            String alias = rest.substring(0, eq).trim();
            String target = rest.substring(eq + 1).trim();
            usingAliasMap.put(alias, target);
            return;
        }

        usingNamespaces.add(rest);

        // Check if this is an extension method namespace
        if (rest.equals("System.Linq") || rest.endsWith(".Linq")) {
            extensionMethodNamespaces.add(rest);
        }
    }

    @Override
    public void enterNamespace_declaration(CSharpParser.Namespace_declarationContext ctx) {
        if (ctx.qi != null) {
            currentNamespace = ctx.qi.getText();
        }
        pushScope();
    }

    @Override
    public void exitNamespace_declaration(CSharpParser.Namespace_declarationContext ctx) {
        currentNamespace = "";
        popScope();
    }

    @Override
    public void enterClass_definition(CSharpParser.Class_definitionContext ctx) {
        if (ctx.identifier() != null) {
            currentClass = ctx.identifier().getText();
            localTypes.add(currentClass);
        }
        pushScope();
    }

    @Override
    public void exitClass_definition(CSharpParser.Class_definitionContext ctx) {
        currentClass = "";
        popScope();
    }

    @Override
    public void enterMethod_declaration(CSharpParser.Method_declarationContext ctx) {
        pushScope();

        if (ctx.formal_parameter_list() != null) {
            processFormalParameterList(ctx.formal_parameter_list());
        }
    }

    private void processFormalParameterList(CSharpParser.Formal_parameter_listContext formalParams) {
        if (formalParams == null) return;

        try {
            if (formalParams.fixed_parameters() != null) {
                processFixedParameters(formalParams.fixed_parameters());
            }

            if (formalParams.parameter_array() != null) {
                processParameterArray(formalParams.parameter_array());
            }
        } catch (Exception e) {
            // Silently continue on parameter processing errors
        }
    }

    private void processFixedParameters(CSharpParser.Fixed_parametersContext fixedParams) {
        if (fixedParams == null || fixedParams.fixed_parameter() == null) return;

        for (CSharpParser.Fixed_parameterContext fixedParam : fixedParams.fixed_parameter()) {
            try {
                if (fixedParam.arg_declaration() != null) {
                    processArgDeclaration(fixedParam.arg_declaration());
                }
            } catch (Exception e) {
                // Silently continue
            }
        }
    }

    private void processParameterArray(CSharpParser.Parameter_arrayContext paramArray) {
        if (paramArray == null || paramArray.identifier() == null) return;

        try {
            String paramName = paramArray.identifier().getText();
            String paramType = "Array";

            if (paramArray.array_type() != null) {
                paramType = extractBaseType(paramArray.array_type().getText());
            }

            declareVariable(paramName, paramType);
            parameterTypes.put(paramName, paramType);
        } catch (Exception e) {
            // Silently continue
        }
    }

    private void processArgDeclaration(CSharpParser.Arg_declarationContext argDecl) {
        if (argDecl == null || argDecl.identifier() == null) return;

        try {
            String paramName = argDecl.identifier().getText();
            String paramType = "object";

            if (argDecl.type_() != null) {
                paramType = extractBaseType(argDecl.type_().getText());
            }

            declareVariable(paramName, paramType);
            parameterTypes.put(paramName, paramType);
        } catch (Exception e) {
            // Silently continue
        }
    }

    @Override
    public void exitMethod_declaration(CSharpParser.Method_declarationContext ctx) {
        popScope();
    }

    @Override
    public void enterConstructor_declaration(CSharpParser.Constructor_declarationContext ctx) {
        pushScope();

        if (ctx.formal_parameter_list() != null) {
            processFormalParameterList(ctx.formal_parameter_list());
        }
    }

    @Override
    public void exitConstructor_declaration(CSharpParser.Constructor_declarationContext ctx) {
        popScope();
    }

    @Override
    public void exitField_declaration(CSharpParser.Field_declarationContext ctx) {
        try {
            if (ctx.variable_declarators() != null && ctx.variable_declarators().variable_declarator() != null) {
                String fieldType = getTypeFromParentContext(ctx);

                for (CSharpParser.Variable_declaratorContext declarator : ctx.variable_declarators().variable_declarator()) {
                    if (declarator.identifier() != null) {
                        String fieldName = declarator.identifier().getText();
                        fieldTypes.put(fieldName, fieldType);
                        declareVariable(fieldName, fieldType);
                    }
                }
            }
        } catch (Exception e) {
            // Silently continue
        }
    }

    private String getTypeFromParentContext(CSharpParser.Field_declarationContext fieldCtx) {
        try {
            ParseTree parent = fieldCtx.getParent();
            while (parent != null) {
                if (parent instanceof CSharpParser.Typed_member_declarationContext) {
                    CSharpParser.Typed_member_declarationContext typedMember =
                            (CSharpParser.Typed_member_declarationContext) parent;
                    if (typedMember.type_() != null) {
                        return extractBaseType(typedMember.type_().getText());
                    }
                }
                parent = parent.getParent();
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return "object";
    }

    @Override
    public void exitLocal_variable_declaration(CSharpParser.Local_variable_declarationContext ctx) {
        try {
            if (ctx.local_variable_type() == null || ctx.local_variable_declarator() == null) return;

            String typeText = ctx.local_variable_type().getText();

            for (CSharpParser.Local_variable_declaratorContext declarator : ctx.local_variable_declarator()) {
                if (declarator.identifier() != null) {
                    String varName = declarator.identifier().getText();
                    String finalType = typeText;

                    // Handle var inference
                    if ("var".equals(typeText) && declarator.local_variable_initializer() != null) {
                        String initText = declarator.local_variable_initializer().getText();
                        String inferredType = extractTypeFromNewExpression(initText);
                        if (inferredType != null) {
                            finalType = inferredType;
                        }
                    }

                    // PATCH: keep generics (do NOT strip to base here)
                    // finalType = extractBaseType(finalType);
                    declareVariable(varName, finalType);
                }
            }
        } catch (Exception e) {
            // Silently continue
        }
    }

    // Enhanced token scanning method
    public void runTokenPostPass() {
        if (tokens == null) return;

        List<Token> vis = getVisibleTokens();
        if (vis.isEmpty()) return;

        // Scan for legitimate API calls
        scanForVariableDeclarations(vis);
        scanForConstructors(vis);
        scanForMethodCalls(vis);
        scanForExtensionMethods(vis);     // guarded to avoid Math.Min false positives
        scanForChainedMethodCalls(vis);
        // PATCH: include indexers and static calls
        scanForIndexerMethodCalls(vis);
        scanForStaticMethodCalls(vis);
    }

    private List<Token> getVisibleTokens() {
        List<Token> vis = new ArrayList<>();
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                vis.add(t);
            }
        }
        return vis;
    }

    private void scanForVariableDeclarations(List<Token> vis) {
        for (int i = 0; i < vis.size() - 3; i++) {
            // Pattern: var varName = new Type(...)
            if ("var".equals(vis.get(i).getText()) &&
                    isIdentifierLike(vis.get(i + 1).getText()) &&
                    "=".equals(vis.get(i + 2).getText())) {
                String varName = vis.get(i + 1).getText();
                String inferredType = findNewTypeAfterEquals(vis, i + 2);

                if (inferredType != null) {
                    declareVariable(varName, inferredType);
                }
            }
            // Pattern: Type varName = new Type(...)
            else if (isTypePattern(vis, i)) {
                String type = vis.get(i).getText();
                String varName = vis.get(i + 1).getText();

                if (isIdentifierLike(varName) && "=".equals(vis.get(i + 2).getText())) {
                    String initType = findNewTypeAfterEquals(vis, i + 2);
                    String finalType = initType != null ? initType : type;
                    declareVariable(varName, finalType);
                }
            }
        }
    }

    private void scanForConstructors(List<Token> vis) {
        for (int i = 0; i < vis.size() - 1; i++) {
            if ("new".equals(vis.get(i).getText())) {
                int typeIdx = findNextNonWhitespace(vis, i + 1);
                if (typeIdx < vis.size() && isIdentifierLike(vis.get(typeIdx).getText())) {
                    String type = vis.get(typeIdx).getText();

                    int nextIdx = skipGenerics(vis, typeIdx + 1);
                    nextIdx = findNextNonWhitespace(vis, nextIdx);

                    if (nextIdx < vis.size() && "(".equals(vis.get(nextIdx).getText())) {
                        String fqn = qualifyTypeName(type);
                        if (fqn != null && fqn.contains(".") && !isValueType(type) && !localTypes.contains(type)) {
                            results.add(fqn + ".ctor");
                        }
                    }
                }
            }
        }
    }

    private void scanForMethodCalls(List<Token> vis) {
        for (int i = 0; i < vis.size(); i++) {
            if ("(".equals(vis.get(i).getText())) {
                processMethodCallAt(vis, i);
            }
        }
    }

    private void processMethodCallAt(List<Token> vis, int parenIdx) {
        String method = extractMethodName(vis, parenIdx);
        if (method == null || method.length() == 0) return;

        String qualifier = extractQualifier(vis, parenIdx, method);

        if (qualifier == null) {
            handleUnqualifiedMethod(method);
        } else {
            handleQualifiedMethod(qualifier, method);
        }
    }

    private String extractMethodName(List<Token> vis, int parenIdx) {
        int methodIdx = parenIdx - 1;
        while (methodIdx >= 0 && ".".equals(vis.get(methodIdx).getText())) methodIdx--;

        if (methodIdx >= 0 && isIdentifierLike(vis.get(methodIdx).getText())) {
            return vis.get(methodIdx).getText();
        }
        return null;
    }

    private String extractQualifier(List<Token> vis, int parenIdx, String method) {
        List<String> parts = new ArrayList<>();
        int i = parenIdx - 1;

        // Skip method name
        while (i >= 0 && ".".equals(vis.get(i).getText())) i--;
        if (i >= 0 && isIdentifierLike(vis.get(i).getText())) i--;

        // Collect qualifier parts
        while (i >= 0) {
            String token = vis.get(i).getText();

            if (".".equals(token)) {
                i--;
                if (i >= 0 && isIdentifierLike(vis.get(i).getText())) {
                    parts.add(0, vis.get(i).getText());
                    i--;
                } else break;
            } else if (isIdentifierLike(token)) {
                parts.add(0, token);
                i--;
                break;
            } else if (")".equals(token)) {
                // Handle chained method calls like obj.Method1().Method2()
                i = skipBackwardsThroughParentheses(vis, i);
                if (i >= 0 && isIdentifierLike(vis.get(i).getText())) {
                    // This is a method call result, try to resolve its type
                    String chainedMethod = vis.get(i).getText();
                    String chainedQualifier = extractQualifier(vis, i + 1, chainedMethod);
                    String resultType = resolveMethodReturnType(chainedQualifier, chainedMethod);
                    if (resultType != null) {
                        parts.add(0, resultType);
                    }
                    break;
                }
            } else break;
        }

        return parts.isEmpty() ? null : String.join(".", parts);
    }

    private void handleUnqualifiedMethod(String method) {
        // Check using static types first
        for (String staticType : usingStaticTypes) {
            String fqn = qualifyTypeName(staticType);
            if (fqn != null && fqn.contains(".")) {
                results.add(fqn + "." + method);
                return;
            }
        }

        // Check for common static method calls
        Map<String, String> staticMethods = new HashMap<>();
        staticMethods.put("Parse", "System.Int32.Parse");
        // removed: avoid spurious Object.ToString for instance calls
        // staticMethods.put("ToString", "System.Object.ToString");
        staticMethods.put("Equals", "System.Object.Equals");
        staticMethods.put("GetHashCode", "System.Object.GetHashCode");
        staticMethods.put("GetType", "System.Object.GetType");
        staticMethods.put("Min", "System.Math.Min");
        staticMethods.put("Max", "System.Math.Max");
        staticMethods.put("Abs", "System.Math.Abs");
        staticMethods.put("Sqrt", "System.Math.Sqrt");
        staticMethods.put("Pow", "System.Math.Pow");

        if (staticMethods.containsKey(method)) {
            results.add(staticMethods.get(method));
        }
    }

    private void handleQualifiedMethod(String qualifier, String method) {
        if (qualifier == null || method == null) return;

        try {
            // Special method mappings for common APIs
            Map<String, String> specialMethods = new HashMap<>();
            specialMethods.put("Split", "System.String.Split");
            specialMethods.put("Wait", "System.Threading.Tasks.Task.Wait");
            specialMethods.put("StartNew", "System.Threading.Tasks.TaskFactory.StartNew");
            specialMethods.put("ContinueWith", "System.Threading.Tasks.Task.ContinueWith");
            specialMethods.put("Select", "System.Linq.Enumerable.Select");
            specialMethods.put("Where", "System.Linq.Enumerable.Where");
            specialMethods.put("First", "System.Linq.Enumerable.First");
            specialMethods.put("FirstOrDefault", "System.Linq.Enumerable.FirstOrDefault");
            specialMethods.put("Single", "System.Linq.Enumerable.Single");
            specialMethods.put("SingleOrDefault", "System.Linq.Enumerable.SingleOrDefault");
            specialMethods.put("Any", "System.Linq.Enumerable.Any");
            specialMethods.put("All", "System.Linq.Enumerable.All");
            specialMethods.put("Count", "System.Linq.Enumerable.Count");
            specialMethods.put("Sum", "System.Linq.Enumerable.Sum");
            specialMethods.put("Average", "System.Linq.Enumerable.Average");
            // removed: LINQ Min/Max here; handle based on qualifier/guards
            specialMethods.put("OrderBy", "System.Linq.Enumerable.OrderBy");
            specialMethods.put("OrderByDescending", "System.Linq.Enumerable.OrderByDescending");
            specialMethods.put("ThenBy", "System.Linq.Enumerable.ThenBy");
            specialMethods.put("ThenByDescending", "System.Linq.Enumerable.ThenByDescending");
            specialMethods.put("GroupBy", "System.Linq.Enumerable.GroupBy");
            specialMethods.put("Join", "System.Linq.Enumerable.Join");
            specialMethods.put("ToArray", "System.Linq.Enumerable.ToArray");
            specialMethods.put("ToList", "System.Linq.Enumerable.ToList");
            specialMethods.put("ToDictionary", "System.Linq.Enumerable.ToDictionary");
            specialMethods.put("ToLookup", "System.Linq.Enumerable.ToLookup");

            if (specialMethods.containsKey(method)) {
                results.add(specialMethods.get(method));
                return;
            }

            // Handle Task.Factory.StartNew specially
            if ("Task.Factory".equals(qualifier) && "StartNew".equals(method)) {
                results.add("System.Threading.Tasks.TaskFactory.StartNew");
                return;
            }

            // Handle Math.Min/Max specially
            if ("Math".equals(qualifier) && ("Min".equals(method) || "Max".equals(method))) {
                results.add("System.Math." + method);
                return;
            }

            // Resolve qualifier type and add method
            String resolvedType = resolveQualifierType(qualifier);
            if (resolvedType != null && isValidQualifiedType(resolvedType)) {
                results.add(resolvedType + "." + method);
            }
        } catch (Exception e) {
            // Silently continue on errors
        }
    }

    private void scanForExtensionMethods(List<Token> vis) {
        // PATCH: guard to avoid falsely tagging Math.Min etc as Enumerable.Min
        for (int i = 0; i < vis.size() - 3; i++) {
            if (isIdentifierLike(vis.get(i).getText()) &&
                    ".".equals(vis.get(i + 1).getText()) &&
                    isIdentifierLike(vis.get(i + 2).getText()) &&
                    "(".equals(vis.get(i + 3).getText())) {

                String left = vis.get(i).getText();
                String methodName = vis.get(i + 2).getText();

                // Skip if left is a known static type or qualifies to a type via usings/aliases
                if (isKnownStaticType(left)) continue;
                String leftAsType = qualifyTypeName(left);
                if (leftAsType != null && leftAsType.contains(".")) continue;

                if (knownExtensionMethods.contains(methodName)) {
                    results.add("System.Linq.Enumerable." + methodName);
                }
            }
        }
    }

    private void scanForChainedMethodCalls(List<Token> vis) {
        for (int i = 0; i < vis.size() - 4; i++) {
            // Pattern: obj.Method1().Method2()
            if (isIdentifierLike(vis.get(i).getText()) &&
                    ".".equals(vis.get(i + 1).getText()) &&
                    isIdentifierLike(vis.get(i + 2).getText()) &&
                    "(".equals(vis.get(i + 3).getText())) {

                int closeParenIdx = findMatchingCloseParenthesis(vis, i + 3);
                if (closeParenIdx > 0 && closeParenIdx + 2 < vis.size() &&
                        ".".equals(vis.get(closeParenIdx + 1).getText()) &&
                        isIdentifierLike(vis.get(closeParenIdx + 2).getText())) {

                    String firstMethod = vis.get(i + 2).getText();
                    String secondMethod = vis.get(closeParenIdx + 2).getText();
                    String objName = vis.get(i).getText();

                    // Handle special case for sr.ReadLine().Split()
                    if ("ReadLine".equals(firstMethod) && "Split".equals(secondMethod)) {
                        String objType = resolveQualifierType(objName);
                        if ("System.IO.StreamReader".equals(objType)) {
                            results.add("System.String.Split");
                        }
                    }
                }
            }
        }
    }

    private static final Set<String> knownExtensionMethods = Set.of(
            "Select", "Where", "First", "FirstOrDefault", "Single", "SingleOrDefault",
            "Any", "All", "Count", "Sum", "Average", "Min", "Max", "OrderBy", "OrderByDescending",
            "ThenBy", "ThenByDescending", "GroupBy", "Join", "ToArray", "ToList", "ToDictionary", "ToLookup"
    );

    // Helper methods
    private String resolveQualifierType(String qualifier) {
        if (qualifier == null) return null;

        try {
            // Check if qualifier is a known type
            String qualifiedType = qualifyTypeName(qualifier);
            if (qualifiedType != null && qualifiedType.contains(".")) {
                return qualifiedType;
            }

            // Resolve as variable
            String[] parts = qualifier.split("\\.");
            String leftmost = parts[0];
            String varType = lookupVariable(leftmost);

            if (varType != null) {
                String qualifiedVarType = qualifyTypeName(varType);
                if (qualifiedVarType != null) {
                    return qualifiedVarType;
                }
            }

            // Check field types
            if (fieldTypes.containsKey(leftmost)) {
                return qualifyTypeName(fieldTypes.get(leftmost));
            }

            // Check parameter types
            if (parameterTypes.containsKey(leftmost)) {
                return qualifyTypeName(parameterTypes.get(leftmost));
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveMethodReturnType(String qualifier, String method) {
        // Simple method return type resolution for common cases
        Map<String, String> methodReturnTypes = new HashMap<>();
        methodReturnTypes.put("ReadLine", "System.String");
        methodReturnTypes.put("ToString", "System.String");
        methodReturnTypes.put("Parse", "System.Int32");

        if (methodReturnTypes.containsKey(method)) {
            return methodReturnTypes.get(method);
        }

        return null;
    }

    private String qualifyTypeName(String simpleOrQualified) {
        if (simpleOrQualified == null) return null;

        // Already qualified
        if (simpleOrQualified.contains(".")) return simpleOrQualified;

        // Check well-known types
        String known = WELL_KNOWN_SIMPLE.get(simpleOrQualified);
        if (known != null) return known;

        // Check aliases
        if (usingAliasMap.containsKey(simpleOrQualified)) {
            return usingAliasMap.get(simpleOrQualified);
        }

        // Try with using namespaces
        for (String ns : usingNamespaces) {
            if (Character.isUpperCase(simpleOrQualified.charAt(0))) {
                return ns + "." + simpleOrQualified;
            }
        }

        return simpleOrQualified;
    }

    private boolean isValidQualifiedType(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) return false;

        // Must contain a dot to be qualified
        if (!typeName.contains(".")) return false;

        // Should not contain invalid characters that suggest parsing errors
        if (typeName.contains("System.System.") || typeName.length() == 1) return false;

        // Should start with a valid namespace
        return typeName.startsWith("System.") ||
                typeName.startsWith("Microsoft.") ||
                Character.isUpperCase(typeName.charAt(0));
    }

    // Helper methods for token analysis
    private boolean isTypePattern(List<Token> vis, int i) {
        if (i + 1 >= vis.size()) return false;
        String token1 = vis.get(i).getText();
        String token2 = vis.get(i + 1).getText();
        return isIdentifierLike(token1) &&
                (WELL_KNOWN_SIMPLE.containsKey(token1) || Character.isUpperCase(token1.charAt(0))) &&
                isIdentifierLike(token2) &&
                !CS_KEYWORDS.contains(token2);
    }

    // PATCH: improved to capture generics after 'new'
    private String findNewTypeAfterEquals(List<Token> vis, int equalsIdx) {
        for (int i = equalsIdx + 1; i < Math.min(equalsIdx + 10, vis.size()); i++) {
            if ("new".equals(vis.get(i).getText()) && i + 1 < vis.size()) {
                String base = vis.get(i + 1).getText();
                int j = i + 2;
                if (j < vis.size() && "<".equals(vis.get(j).getText())) {
                    int end = skipGenerics(vis, j);
                    StringBuilder sb = new StringBuilder();
                    for (int k = i + 1; k < end && k < vis.size(); k++) sb.append(vis.get(k).getText());
                    return sb.toString();
                }
                return base;
            }
        }
        return null;
    }

    private String extractBaseType(String typeText) {
        if (typeText == null) return null;
        int genericStart = typeText.indexOf('<');
        if (genericStart > 0) typeText = typeText.substring(0, genericStart);
        int arrayStart = typeText.indexOf('[');
        if (arrayStart > 0) typeText = typeText.substring(0, arrayStart);
        return typeText.trim();
    }

    private String extractTypeFromNewExpression(String expr) {
        if (expr == null || !expr.contains("new")) return null;
        int newIdx = expr.indexOf("new");
        String afterNew = expr.substring(newIdx + 3).trim();
        int parenIdx = afterNew.indexOf('(');
        int bracketIdx = afterNew.indexOf('[');
        int endIdx = afterNew.length();
        if (parenIdx > 0) endIdx = Math.min(endIdx, parenIdx);
        if (bracketIdx > 0) endIdx = Math.min(endIdx, bracketIdx);
        return afterNew.substring(0, endIdx).trim();
    }

    private int skipGenerics(List<Token> vis, int startIdx) {
        int i = startIdx;
        if (i < vis.size() && "<".equals(vis.get(i).getText())) {
            int depth = 1;
            i++;
            while (i < vis.size() && depth > 0) {
                if ("<".equals(vis.get(i).getText())) depth++;
                else if (">".equals(vis.get(i).getText())) depth--;
                i++;
            }
        }
        return i;
    }

    private int findNextNonWhitespace(List<Token> vis, int startIdx) {
        int i = startIdx;
        while (i < vis.size() && vis.get(i).getText().trim().isEmpty()) {
            i++;
        }
        return i;
    }

    private int skipBackwardsThroughParentheses(List<Token> vis, int closeParenIdx) {
        int depth = 1;
        int i = closeParenIdx - 1;
        while (i >= 0 && depth > 0) {
            if (")".equals(vis.get(i).getText())) depth++;
            else if ("(".equals(vis.get(i).getText())) depth--;
            i--;
        }
        return i;
    }

    private int findMatchingCloseParenthesis(List<Token> vis, int openParenIdx) {
        int depth = 1;
        for (int i = openParenIdx + 1; i < vis.size(); i++) {
            if ("(".equals(vis.get(i).getText())) depth++;
            else if (")".equals(vis.get(i).getText())) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean isValueType(String typeName) {
        return typeName != null && (
                WELL_KNOWN_SIMPLE.containsKey(typeName) &&
                        WELL_KNOWN_SIMPLE.get(typeName).startsWith("System.") &&
                        (typeName.equals("int") || typeName.equals("string") || typeName.equals("bool") ||
                                typeName.equals("double") || typeName.equals("float") || typeName.equals("decimal") ||
                                typeName.equals("char") || typeName.equals("byte") || typeName.equals("sbyte") ||
                                typeName.equals("short") || typeName.equals("ushort") || typeName.equals("uint") ||
                                typeName.equals("long") || typeName.equals("ulong"))
        );
    }

    private static boolean isIdentifierLike(String s) {
        if (s == null || s.isEmpty()) return false;
        if (CS_KEYWORDS.contains(s)) return false;
        char c0 = s.charAt(0);
        return Character.isLetter(c0) || c0 == '_';
    }

    // New methods for enhanced detection

    private void scanForIndexerMethodCalls(List<Token> vis) {
        // Pattern: obj[index].Method()
        for (int i = 0; i < vis.size() - 5; i++) {
            if (isIdentifierLike(vis.get(i).getText()) &&
                    "[".equals(vis.get(i + 1).getText())) {

                int closeBracketIdx = findMatchingCloseBracket(vis, i + 1);
                if (closeBracketIdx > 0 && closeBracketIdx + 3 < vis.size() &&
                        ".".equals(vis.get(closeBracketIdx + 1).getText()) &&
                        isIdentifierLike(vis.get(closeBracketIdx + 2).getText()) &&
                        "(".equals(vis.get(closeBracketIdx + 3).getText())) {

                    String arrayVar = vis.get(i).getText();
                    String method = vis.get(closeBracketIdx + 2).getText();

                    // Resolve the element type of the array/collection
                    String elementType = resolveElementType(arrayVar);
                    if (elementType != null && isValidQualifiedType(elementType)) {
                        results.add(elementType + "." + method);
                    }
                }
            }
        }
    }

    private void scanForStaticMethodCalls(List<Token> vis) {
        // Enhanced static method detection: Type.Method()
        for (int i = 0; i < vis.size() - 3; i++) {
            if (isIdentifierLike(vis.get(i).getText()) &&
                    ".".equals(vis.get(i + 1).getText()) &&
                    isIdentifierLike(vis.get(i + 2).getText()) &&
                    "(".equals(vis.get(i + 3).getText())) {

                String typeName = vis.get(i).getText();
                String methodName = vis.get(i + 2).getText();

                // Check if this looks like a static method call
                if (isKnownStaticType(typeName)) {
                    String fullyQualifiedType = qualifyTypeName(typeName);
                    if (fullyQualifiedType != null && fullyQualifiedType.contains(".")) {
                        results.add(fullyQualifiedType + "." + methodName);
                    }
                }
            }
        }
    }


    private String resolveElementType(String collectionVar) {
        // Look up the collection type and determine element type (preserve generics if available)
        String collectionType = lookupVariable(collectionVar);
        if (collectionType == null) collectionType = fieldTypes.get(collectionVar);
        if (collectionType == null) collectionType = parameterTypes.get(collectionVar);

        if (collectionType == null) return null;

        // Normalize simple names to fully-qualified where possible
        String qualified = qualifyTypeName(collectionType);

        // Arrays: T[] or fully-qualified T[]
        if (qualified.endsWith("[]")) {
            String elem = qualified.substring(0, qualified.length()-2);
            return qualifyTypeName(elem);
        }

        // Generic types: erasure<args>
        TypeInfo info = parseGenericType(collectionType);
        String erasure = info.erasure != null ? info.erasure : qualified;

        // Handle List<T> and similar
        String erasureQualified = qualifyTypeName(erasure);
        if (erasureQualified != null) {
            // System.Collections.Generic.List<T>
            if (erasureQualified.equals("System.Collections.Generic.List") && !info.typeArgs.isEmpty()) {
                String t = info.typeArgs.get(0);
                return qualifyTypeName(t);
            }
            // System.Collections.Generic.Dictionary<K,V> -> value type as element for indexer by key
            if (erasureQualified.equals("System.Collections.Generic.Dictionary") && info.typeArgs.size() == 2) {
                String v = info.typeArgs.get(1);
                return qualifyTypeName(v);
            }
        }

        // Fallbacks
        if (qualified.startsWith("System.Threading.Tasks.Task")) {
            return "System.Threading.Tasks.Task";
        }
        if (qualified.startsWith("System.Collections.Generic.List")) {
            // Try to extract generic argument from fully-qualified form
            TypeInfo qinfo = parseGenericType(qualified);
            if (!qinfo.typeArgs.isEmpty()) return qualifyTypeName(qinfo.typeArgs.get(0));
            return "System.Object";
        }
        return null;
    }

    private String inferGenericTypeParameter(String varName, String baseType) {
        // Simple inference based on variable patterns
        if (varName.toLowerCase().contains("task") || baseType.equals("List")) {
            // Check for common patterns like List<Task>
            if (varName.equals("tl") || varName.contains("task")) {
                return "System.Threading.Tasks.Task";
            }
        }
        return "System.Object"; // Safe fallback
    }

    private String inferArrayElementType(String varName) {
        // Simple array element type inference
        return "System.Object";
    }

    private boolean isKnownStaticType(String typeName) {
        // Types that commonly have static methods
        Set<String> staticTypes = Set.of(
                "Math", "Console", "File", "Directory", "Path", "Environment",
                "Convert", "Array", "String", "DateTime", "Guid", "Task",
                "Thread", "Type", "GC", "Random"
        );

        return staticTypes.contains(typeName) || WELL_KNOWN_SIMPLE.containsKey(typeName);
    }

    private boolean isCommonMathMethod(String method) {
        Set<String> mathMethods = Set.of(
                "Min", "Max", "Abs", "Sqrt", "Pow", "Sin", "Cos", "Tan",
                "Floor", "Ceiling", "Round", "Log", "Log10", "Exp"
        );

        return mathMethods.contains(method);
    }

    private int findMatchingCloseBracket(List<Token> vis, int openBracketIdx) {
        int depth = 1;
        for (int i = openBracketIdx + 1; i < vis.size(); i++) {
            if ("[".equals(vis.get(i).getText())) depth++;
            else if ("]".equals(vis.get(i).getText())) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
