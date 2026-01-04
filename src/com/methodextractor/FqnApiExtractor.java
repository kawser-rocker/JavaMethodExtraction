package com.methodextractor;

import java.util.*;
import java.util.regex.Pattern;

import JavaParser.JavaParserBaseListener;
import JavaParser.JavaParser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/**
 * Fully-qualified API-call extractor on top of the standard ANTLR v4 Java grammar
 * (JavaLexer / JavaParser / JavaParserBaseListener).
 *
 * This version keeps your original structure, and adds:
 *  1) Constructor capture: records "pkg.Type.<init>" for non-local "new Type(...)" (skips anonymous classes).
 *  2) Fully-qualified receivers: handles calls like "java.util.Collections.sort(...)".
 *  3) System streams via static import: handles "out.println" / "err.println" / "in.read" when System streams are statically imported.
 *  4) Monitor methods on this/super: records java.lang.Object.wait/notify/notifyAll for calls on "this" as well.
 */
public class FqnApiExtractor extends JavaParserBaseListener {

    // ------------------- Import bookkeeping -------------------
    private static final Set<String> JAVA_LANG_SIMPLE = Set.of(
            "String","Object","System","Thread","Math","Integer","Long","Double",
            "Float","Short","Byte","Character","Boolean","StringBuilder",
            "StringBuffer","Class","Throwable","Exception","RuntimeException","Error"
    );

    // Normal imports: simple class -> FQN ("List" -> "java.util.List")
    private final Map<String, String> normalImportMap = new HashMap<>();

    // Normal wildcard imports: package prefixes that ended with .* (e.g., "com.foo")
    private final Set<String> normalWildcardPackages = new LinkedHashSet<>();

    // Static imports of specific methods: simple method name -> fqn "pkg.Class.method"
    private final Map<String, String> staticImportMap = new HashMap<>();

    // Static import wildcards: set of class FQNs that were imported with "import static ...*;"
    private final Set<String> staticImportWildcardClasses = new LinkedHashSet<>();

    // Track package for local default-qualification if needed
    private String currentPackage = null;

    // ------------------- Variable scopes -------------------
    /** Stack of "name -> declaredType (simple or qualified)" */
    private final Deque<Map<String, String>> scopes = new ArrayDeque<>();

    private Map<String, String> currentScope() { return scopes.peek(); }
    private void pushScope() { scopes.push(new HashMap<>()); }
    private void popScope()  { if (!scopes.isEmpty()) scopes.pop(); }

    private void declare(String name, String typeName) {
        if (name == null || typeName == null) return;
        Map<String, String> s = currentScope();
        if (s != null) s.put(name, typeName);
    }
    private String lookup(String name) {
        for (Map<String, String> s : scopes) {
            if (s.containsKey(name)) return s.get(name);
        }
        return null;
    }

    // ------------------- Local type tracking (for filtering) -------------------
    /** Simple names of classes/interfaces declared in this compilation unit. */
    private final Set<String> localTypes = new HashSet<>();

    // ------------------- Results -------------------
    private final List<String> results = new ArrayList<>();

    /** Unique, order-preserving results. */
    public List<String> getResults() {
        return new ArrayList<>(new LinkedHashSet<>(results));
    }

    // ------------------- Utilities -------------------
    private static final Pattern CAMEL = Pattern.compile("[A-Z].*");

    private static String stripGenerics(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]*>", "");
    }
    private static String tidyDots(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s*\\.\\s*", ".").trim();
    }
    private static boolean hasDot(String s) { return s != null && s.contains("."); }
    private static boolean isCamel(String s) { return s != null && CAMEL.matcher(s).matches(); }
    private static String simpleNameOf(String fqnOrSimple) {
        if (fqnOrSimple == null) return null;
        int idx = fqnOrSimple.lastIndexOf('.');
        return idx < 0 ? fqnOrSimple : fqnOrSimple.substring(idx + 1);
    }

    /**
     * Try to qualify a simple class name using imports / wildcard packages.
     * NOTE: No broad java.lang fallback (to avoid turning local classes into java.lang.X),
     * but we do allow a small safe-list of core java.lang types.
     */
    private String qualifySimpleClass(String simpleOrQualified) {
        if (simpleOrQualified == null) return null;
        String t = stripGenerics(simpleOrQualified);
        if (hasDot(t)) return t; // already qualified

        String viaImport = normalImportMap.get(t);
        if (viaImport != null) return viaImport;

        // Wildcard package heuristic (only if exactly one wildcard package is present)
        if (isCamel(t) && normalWildcardPackages.size() == 1) {
            String pkg = normalWildcardPackages.iterator().next();
            return pkg + "." + t;
        }

        // Safe java.lang mapping for core types only
        if (JAVA_LANG_SIMPLE.contains(t)) return "java.lang." + t;

        return t; // leave as-is; may be local
    }

    /** Resolve a variable name to a fully-qualified declared type (if known). */
    private String expandLeftmostIdentifierToFqn(String leftmost) {
        if (leftmost == null) return null;
        String decl = lookup(leftmost);
        if (decl == null) return null;
        String type = stripGenerics(decl);
        if (hasDot(type)) return type;

        // Try to qualify simple name
        return qualifySimpleClass(type);
    }

    private void record(String qualifierTypeFqn, String method) {
        if (qualifierTypeFqn == null || method == null) return;
        String q = tidyDots(stripGenerics(qualifierTypeFqn));
        if (q == null || q.isEmpty()) return;

        // Skip local types
        String simple = simpleNameOf(q);
        if (localTypes.contains(simple)) return;

        // Require a package to avoid default-package locals
        if (!q.contains(".")) return;

        results.add(q + "." + method);
    }

    private static final boolean INCLUDE_INIT_SUFFIX = true;

    // Constructors: record the class FQN only (no .<init>)
    private void recordCtor(String qualifierTypeFqn) {
        if (qualifierTypeFqn == null) return;
        String q = tidyDots(stripGenerics(qualifierTypeFqn));
        if (q == null || q.isEmpty()) return;
        String simple = simpleNameOf(q);
        if (localTypes.contains(simple)) return;
        if (!q.contains(".")) return;

        results.add(INCLUDE_INIT_SUFFIX ? (q + ".<init>") : q);
    }



    // ------------------- System streams via static import -------------------
    private boolean staticImportSystemWildcard = false;
    private final Set<String> staticImportSystemMembers = new HashSet<>(); // e.g., out, err, in

    private boolean isSystemStreamIdentifier(String ident) {
        if (ident == null) return false;
        if (ident.equals("out") || ident.equals("err") || ident.equals("in")) {
            return staticImportSystemWildcard || staticImportSystemMembers.contains(ident);
        }
        return false;
    }

    // ------------------- Listener (structure/scopes) -------------------
    @Override public void enterCompilationUnit(JavaParser.CompilationUnitContext ctx) { pushScope(); }
    @Override public void exitCompilationUnit(JavaParser.CompilationUnitContext ctx)  { popScope();  }

    @Override
    public void exitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        if (ctx.qualifiedName() != null) currentPackage = ctx.qualifiedName().getText();
    }

    @Override
    public void exitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        if (ctx.qualifiedName() == null) return;

        String qn = ctx.qualifiedName().getText(); // e.g., java.util.List or com.x.Y
        boolean isStatic = ctx.STATIC() != null;
        boolean wildcard = ctx.getText().contains(".*;");

        if (!isStatic) {
            if (wildcard) {
                // import com.foo.*;
                normalWildcardPackages.add(qn);
            } else {
                // import com.foo.Bar;
                int last = qn.lastIndexOf('.');
                if (last >= 0) normalImportMap.put(qn.substring(last + 1), qn);
            }
        } else {
            if (wildcard) {
                // import static com.foo.Bar.*;
                staticImportWildcardClasses.add(qn);
                if ("java.lang.System".equals(qn)) {
                    staticImportSystemWildcard = true;
                }
            } else {
                // import static com.foo.Bar.member;
                int last = qn.lastIndexOf('.');
                if (last >= 0) {
                    String classFqn = qn.substring(0, last);
                    String member  = qn.substring(last + 1);
                    // If it looks like a method name, store method mapping; otherwise treat as field
                    if (Character.isLowerCase(member.charAt(0))) {
                        // Heuristic: java allows method names starting lower; this covers both
                        staticImportMap.put(member, classFqn + "." + member);
                    } else {
                        staticImportMap.put(member, classFqn + "." + member);
                    }
                    if ("java.lang.System".equals(classFqn) &&
                            ("out".equals(member) || "err".equals(member) || "in".equals(member))) {
                        staticImportSystemMembers.add(member);
                    }
                }
            }
        }
    }

    // ------------------- Declarations → var -> type -------------------
    @Override
    public void enterClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        pushScope();
        if (ctx.IDENTIFIER() != null) localTypes.add(ctx.IDENTIFIER().getText());
    }
    @Override
    public void exitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        popScope();
    }

    @Override
    public void exitFieldDeclaration(JavaParser.FieldDeclarationContext ctx) {
        // fieldDeclaration : typeType variableDeclarators ';'
        String type = stripGenerics(ctx.typeType().getText());
        for (JavaParser.VariableDeclaratorContext v : ctx.variableDeclarators().variableDeclarator()) {
            String name = v.variableDeclaratorId().getText();
            declare(name, type);
        }
    }

    @Override
    public void exitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
        // localVariableDeclaration : variableModifier* typeType variableDeclarators
        String type = stripGenerics(ctx.typeType().getText());
        JavaParser.VariableDeclaratorsContext vs = ctx.variableDeclarators();
        if (vs != null) {
            for (JavaParser.VariableDeclaratorContext v : vs.variableDeclarator()) {
                String name = v.variableDeclaratorId().getText();
                declare(name, type);
            }
        }
    }

    @Override
    public void exitFormalParameter(JavaParser.FormalParameterContext ctx) {
        // formalParameter : variableModifier* typeType variableDeclaratorId
        declare(ctx.variableDeclaratorId().getText(), stripGenerics(ctx.typeType().getText()));
    }

    @Override
    public void exitLastFormalParameter(JavaParser.LastFormalParameterContext ctx) {
        // varargs parameter
        declare(ctx.variableDeclaratorId().getText(), stripGenerics(ctx.typeType().getText()) + "[]");
    }

    @Override
    public void exitEnhancedForControl(JavaParser.EnhancedForControlContext ctx) {
        // enhancedForControl : variableModifier* typeType variableDeclaratorId ':' expression
        declare(ctx.variableDeclaratorId().getText(), stripGenerics(ctx.typeType().getText()));
    }

    @Override
    public void exitCatchClause(JavaParser.CatchClauseContext ctx) {
        // catchClause : CATCH '(' variableModifier* catchType Identifier ')' block
        String type = ctx.catchType().getText(); // may be A | B
        if (type.contains("|")) type = type.split("\\|")[0];
        declare(ctx.IDENTIFIER().getText(), stripGenerics(type));
    }

    // ------------------- Constructor capture -------------------
    @Override
    public void exitCreator(JavaParser.CreatorContext ctx) {
        // creator : createdName (arrayCreatorRest | classCreatorRest)
        if (ctx.createdName() == null || ctx.classCreatorRest() == null) return;

        // If this is an anonymous class "new X(...) { ... }" skip it (not an API ctor).
        String rest = ctx.classCreatorRest().getText();
        if (rest != null && rest.contains("{")) return;

        String name = ctx.createdName().getText();
        // Drop generics if any
        name = stripGenerics(name);
        String fqn = qualifySimpleClass(name);
        recordCtor(fqn);
    }

    // ------------------- Method invocation capture & resolution -------------------
    @Override
    public void exitExpression(JavaParser.ExpressionContext ctx) {
        // Match: <expression> '.' methodCall
        if (ctx.bop == null || ctx.bop.getType() != JavaParser.DOT) return;
        JavaParser.MethodCallContext mc = ctx.methodCall();
        if (mc == null) return;

        String methodName = null;
        if (mc.IDENTIFIER() != null) {
            methodName = mc.IDENTIFIER().getText();
        } else if (mc.THIS() != null || mc.SUPER() != null) {
            // Calls like this.wait() / super.wait()
            String m = (mc.THIS() != null || mc.SUPER() != null) ? (mc.THIS()!=null? "this" : "super") : null;
            // We'll handle monitor methods below after name extraction
        }

        if (methodName == null) return;
        if (ctx.expression() == null || ctx.expression().isEmpty()) return;
        String leftExprText = ctx.expression(0).getText();

        // Special: System.out/err/in
        if (leftExprText.equals("System.out") || leftExprText.equals("System.err") || leftExprText.equals("System.in")) {
            record("java.io.PrintStream", methodName);
            return;
        }
        // Special: static-imported System streams: out/err/in
        if (isSystemStreamIdentifier(leftExprText)) {
            record("java.io.PrintStream", methodName);
            return;
        }

        // Handle "new Type(...).method()" → receiver class = Type
        if (leftExprText.startsWith("new")) {
            String t = leftExprText.substring(3).trim();
            t = t.replaceFirst("\\s*<[^>]*>", ""); // drop generics
            t = t.replaceFirst("\\s*\\(.*", "");   // drop ctor args and after
            t = t.replaceFirst("\\[\\s*\\]", "");  // drop array brackets if any
            String fqn = qualifySimpleClass(t);
            record(fqn, methodName);
            return;
        }

        // Fully-qualified class receiver like "java.util.Collections" or nested "java.util.Map.Entry"
        if (leftExprText.matches("([a-z_][\\w]*\\.)+[A-Z][\\w]*(\\.[A-Z][\\w]*)*")) {
            record(leftExprText, methodName);
            return;
        }

        // If leftmost of the qualifier is a ClassName → static call
        String leftmost = leftExprText.split("\\.")[0];
        if (isCamel(leftmost)) {
            String classFqn = qualifySimpleClass(leftmost);
            record(classFqn, methodName);
            return;
        }

        // Otherwise, treat as variable: resolve declared type
        String varFqn = expandLeftmostIdentifierToFqn(leftmost);
        record(varFqn, methodName);
    }

    @Override
    public void exitMethodCall(JavaParser.MethodCallContext mc) {
        // Unqualified static methods via 'import static'
        if (mc.IDENTIFIER() != null && mc.THIS() == null && mc.SUPER() == null) {
            String name = mc.IDENTIFIER().getText();
            String fqn = staticImportMap.get(name); // "pkg.Class.method"
            if (fqn != null) {
                // Filter out local types as receivers (if someone statically imports own class)
                String cls = fqn.substring(0, Math.max(fqn.lastIndexOf('.'), 0));
                String simple = simpleNameOf(cls);
                if (!localTypes.contains(simple)) {
                    results.add(fqn);
                }
            }
        }

        // Object monitor methods without qualifier (or on this/super)
        String n = (mc.IDENTIFIER()!=null) ? mc.IDENTIFIER().getText() : null;
        if (n != null && ("wait".equals(n) || "notify".equals(n) || "notifyAll".equals(n))) {
            results.add("java.lang.Object." + n);
        }
    }
}