package com.methodextractor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import PythonParser.PythonParser;
import PythonParser.PythonParserBaseListener;

/**
 * Filtered Fully Qualified API call extractor for Python - removes noise and invalid calls.
 * Patches:
 *  • Var-type inference for lhs in:  lhs = base.method(...)
 *  • Receiver qualification at call sites:  name.m() → <inferred-type>.m
 *  • Heuristic for x.is_integer() → builtins.float.is_integer when type unknown
 *  • Known pathlib.Path instance methods mapping (when type inference missed)
 *  • Literal-type emissions disabled by default (flip INCLUDE_LITERAL_TYPES to true to enable)
 */
public class PyFqnApiExtractor extends PythonParserBaseListener {

    /** Toggles */
    private static final boolean INCLUDE_INIT_SUFFIX        = true;
    private static final boolean INCLUDE_CALL_SUFFIX        = true;
    private static final boolean INCLUDE_MAGIC_METHODS      = true;
    private static final boolean INCLUDE_ATTRIBUTE_ACCESS   = true;   // e.g., sys.argv
    private static final boolean INCLUDE_LITERAL_TYPES      = false;  // builtins.list/dict/set/tuple
    private static final boolean DEBUG_MODE                 = false;

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "abs","aiter","all","any","anext","ascii","bin","bool","breakpoint","bytearray","bytes","callable",
            "chr","classmethod","compile","complex","delattr","dict","dir","divmod","enumerate","eval","exec",
            "filter","float","format","frozenset","getattr","globals","hasattr","hash","help","hex","id",
            "input","int","isinstance","issubclass","iter","len","list","locals","map","max","memoryview","min",
            "next","object","oct","open","ord","pow","print","property","range","repr","reversed","round",
            "set","setattr","slice","sorted","staticmethod","str","sum","super","tuple","type","vars","zip"
    ));

    // Allow-list for module attributes (when INCLUDE_ATTRIBUTE_ACCESS is true)
    private static final Map<String, Set<String>> MODULE_ATTRIBUTES = new HashMap<>();
    static {
        MODULE_ATTRIBUTES.put("sys", new HashSet<>(Arrays.asList("argv", "path", "stdout", "stderr", "stdin", "exit", "version")));
        MODULE_ATTRIBUTES.put("os",  new HashSet<>(Arrays.asList("environ", "sep", "pathsep", "linesep", "name")));
        MODULE_ATTRIBUTES.put("math",new HashSet<>(Arrays.asList("pi", "e", "inf", "nan")));
        MODULE_ATTRIBUTES.put("re",  new HashSet<>(Arrays.asList("IGNORECASE", "MULTILINE", "DOTALL")));
    }

    private static final Pattern CAMEL = Pattern.compile("[A-Z].*");
    private static final Pattern STRING_LITERAL        = Pattern.compile("^[\"'].*[\"']$");
    private static final Pattern DOCSTRING_FRAGMENT    = Pattern.compile(".*[\"']{3}.*");

    // Known instance methods (used when var type cannot be inferred)
    private static final Map<String, Set<String>> KNOWN_INSTANCE_METHODS = new HashMap<>();
    static {
        KNOWN_INSTANCE_METHODS.put("pathlib.Path", new HashSet<>(Arrays.asList(
                "read_text","write_text","is_file","with_suffix","resolve","read_bytes","write_bytes",
                "mkdir","exists","is_dir","open","glob","rglob","rename","replace"
        )));
        // add more known classes if useful
    }

    // imports
    private final Map<String,String> aliasMap = new HashMap<>();
    private final Set<String> importedModules = new HashSet<>();
    private final Set<String> starFromModules = new HashSet<>();

    // locals
    private final Set<String> localClasses = new HashSet<>();
    private final Set<String> localFunctions = new HashSet<>();

    // scopes: var -> inferred FQN class
    private final Deque<Map<String,String>> scopes = new ArrayDeque<>();
    private Map<String,String> curScope() { return scopes.peek(); }
    private void pushScope() { scopes.push(new HashMap<>()); }
    private void popScope()  { if (!scopes.isEmpty()) scopes.pop(); }
    private void declareVar(String name, String fqnType) {
        if (name == null || name.isEmpty() || fqnType == null || fqnType.isEmpty()) return;
        Map<String,String> s = curScope(); if (s != null) s.put(name, fqnType);
        logDebug("declareVar: " + name + " -> " + fqnType);
    }
    private String lookupVarType(String name) {
        for (Map<String,String> s : scopes) { String t = s.get(name); if (t != null) return t; }
        return null;
    }

    // results
    private final LinkedHashSet<String> results = new LinkedHashSet<>();
    public List<String> getResults() { return new ArrayList<>(filterValidApiCalls(results)); }

    /* ============================== Filtering ============================== */

    private Set<String> filterValidApiCalls(Set<String> rawResults) {
        Set<String> filtered = new LinkedHashSet<>();
        for (String call : rawResults) {
            if (isValidApiCall(call)) filtered.add(call);
            else logDebug("Filtered out invalid call: " + call);
        }
        return filtered;
    }

    private boolean isValidApiCall(String call) {
        if (call == null || call.trim().isEmpty()) return false;
        if (STRING_LITERAL.matcher(call).matches()) return false;
        if (DOCSTRING_FRAGMENT.matcher(call).matches()) return false;

        if (call.contains("\"") || call.contains("'") || call.contains(" ") ||
                call.contains("\n") || call.contains("(") || call.contains(")")) return false;

        if (!call.contains(".")) return false;

        String[] parts = call.split("\\.");
        if (parts.length < 2) return false;

        for (String id : parts) {
            if (!isValidIdentifier(id)) return false;
        }
        return true;
    }

    private boolean isValidIdentifier(String id) {
        if (id == null || id.isEmpty()) return false;
        if (id.startsWith("__") && id.endsWith("__") && id.length() > 4) return true; // allow magic
        if (!Character.isLetter(id.charAt(0)) && id.charAt(0) != '_') return false;
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /* ============================== Helpers ============================== */

    private static boolean isCamel(String s) { return s != null && CAMEL.matcher(s).matches(); }

    private static List<String> splitDottedNonEmpty(String s) {
        if (s == null) return Collections.emptyList();
        String[] raw = s.split("\\.");
        List<String> out = new ArrayList<>(raw.length);
        for (String r : raw) if (r != null && !r.trim().isEmpty()) out.add(r.trim());
        return out;
    }

    private static String stripLeadingGlue(String s) {
        if (s == null) return null;
        String t = s;
        String[] GLUE_PREFIXES = {"not", "and", "or", "await"};
        for (String kw : GLUE_PREFIXES) {
            if (t.startsWith(kw) && t.length() > kw.length()) {
                char ch = t.charAt(kw.length());
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') {
                    t = t.substring(kw.length());
                    break;
                }
            }
        }
        return t;
    }

    private String resolveBaseName(String name) {
        if (name == null || name.isEmpty()) return null;
        String viaAlias = aliasMap.get(name); if (viaAlias != null) return viaAlias;
        if (importedModules.contains(name)) return name;
        String viaVar = lookupVarType(name); if (viaVar != null) return viaVar;
        if (BUILTINS.contains(name)) return "builtins." + name;
        return name;
    }

    private String resolveDottedExpression(String expression) {
        if (expression == null) return null;
        String expr = stripLeadingGlue(expression.trim());
        if (expr == null || expr.isEmpty()) return null;

        List<String> parts = splitDottedNonEmpty(expr);
        if (parts.isEmpty()) return expr;

        String baseName = parts.get(0);
        String resolvedBase = resolveBaseName(baseName);
        if (resolvedBase == null || resolvedBase.isEmpty()) resolvedBase = baseName;

        StringBuilder result = new StringBuilder(resolvedBase);
        for (int i = 1; i < parts.size(); i++) result.append('.').append(parts.get(i));
        return result.toString();
    }

    private void addResult(String fqn) { if (fqn != null && !fqn.isEmpty()) results.add(fqn); }
    private void logDebug(String message) { if (DEBUG_MODE) System.err.println("[DEBUG] " + message); }

    /* ============================== Parser events ============================== */

    @Override public void enterFile_input(PythonParser.File_inputContext ctx) { pushScope(); }
    @Override public void exitFile_input (PythonParser.File_inputContext ctx) { popScope();  }

    @Override public void enterBlock(PythonParser.BlockContext ctx) { pushScope(); }
    @Override public void exitBlock (PythonParser.BlockContext ctx) { popScope();  }

    @Override public void exitClass_def_raw(PythonParser.Class_def_rawContext ctx) {
        if (ctx.name() != null) localClasses.add(ctx.name().getText());
    }

    @Override public void enterFunction_def_raw(PythonParser.Function_def_rawContext ctx) {
        if (ctx.name() != null) localFunctions.add(ctx.name().getText());
    }

    /* ============================== Imports ============================== */

    @Override public void exitImport_stmt(PythonParser.Import_stmtContext ctx) {
        if (ctx.import_name() != null && ctx.import_name().dotted_as_names() != null) {
            PythonParser.Dotted_as_namesContext names = ctx.import_name().dotted_as_names();
            for (PythonParser.Dotted_as_nameContext d : names.dotted_as_name()) {
                String dotted = (d.dotted_name() != null) ? d.dotted_name().getText() : null;
                if (dotted == null) continue;
                String alias = (d.name() != null) ? d.name().getText() : null;
                if (alias != null) aliasMap.put(alias, dotted);
                else importedModules.add(dotted.split("\\.")[0]);
            }
        }

        if (ctx.import_from() != null) {
            PythonParser.Import_fromContext f = ctx.import_from();
            String base = (f.dotted_name() != null) ? f.dotted_name().getText() : null;

            if (f.import_from_targets() != null) {
                PythonParser.Import_from_targetsContext t = f.import_from_targets();
                if (t.STAR() != null) {
                    if (base != null && !base.isEmpty()) starFromModules.add(base);
                } else if (t.import_from_as_names() != null) {
                    for (PythonParser.Import_from_as_nameContext one : t.import_from_as_names().import_from_as_name()) {
                        String name  = (one.name(0) != null) ? one.name(0).getText() : null;
                        if (name == null) continue;
                        String alias = (one.name(1) != null) ? one.name(1).getText() : name;
                        if (base != null && !base.isEmpty()) aliasMap.put(alias, base + "." + name);
                        else aliasMap.put(alias, name);
                    }
                }
            }
        }
    }

    /* ============================== Assignment & inference ============================== */

    @Override public void exitAssignment(PythonParser.AssignmentContext ctx) {
        String text = ctx.getText();
        if (text == null) return;

        int eq = text.indexOf('=');
        if (eq > 0) {
            String lhs = text.substring(0, eq);
            String rhs = text.substring(eq + 1);
            analyzeAssignment(lhs, rhs);
        } else {
            // Augassign / walrus or no "=" → still scan for calls
            extractCallsFromExpression(text);
        }
    }

    private void analyzeAssignment(String lhs, String rhs) {
        boolean singleName = lhs.matches("[A-Za-z_][A-Za-z_0-9]*");
        boolean inferred = false;

        // (1) v = SomeClass(...)
        if (singleName) {
            int p = rhs.indexOf('(');
            if (p > 0) {
                String callee = rhs.substring(0, p).replaceAll("\\)$", "");
                String fqn = resolveDottedExpression(callee);
                if (fqn != null && !fqn.isEmpty()) {
                    String last = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if (isCamel(last)) {
                        declareVar(lhs, fqn);
                        inferred = true;
                    }
                }
            }
        }

        // (2) v = base.meth(...), copy base type to v if known (e.g., out_path = path.with_suffix(...))
        if (singleName && !inferred) {
            int dot = rhs.indexOf('.');
            int par = rhs.indexOf('(');
            if (dot > 0 && par > dot) {
                String base = rhs.substring(0, dot).trim();
                if (base.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                    String baseType = lookupVarType(base);
                    if (baseType != null && !baseType.isEmpty()) {
                        declareVar(lhs, baseType);
                        inferred = true;
                    } else {
                        // As a fallback, recognize common Path-returning methods
                        String method = rhs.substring(dot + 1, par);
                        if (KNOWN_INSTANCE_METHODS.getOrDefault("pathlib.Path", Collections.emptySet()).contains(method)) {
                            declareVar(lhs, "pathlib.Path");
                            inferred = true;
                        }
                    }
                }
            }
        }

        // (3) Literal type inference (optional)
        if (singleName && !inferred && INCLUDE_LITERAL_TYPES) {
            String lit = inferLiteralTypeFqn(rhs);
            if (lit != null) declareVar(lhs, lit);
        }

        // Scan RHS for calls as well
        extractCallsFromExpression(rhs);
    }

    /** Very light literal inference to qualify common method calls on variables. */
    private String inferLiteralTypeFqn(String rhs) {
        if (rhs == null) return null;
        String s = rhs.trim();
        if (s.isEmpty()) return null;

        while (s.startsWith("(") && s.endsWith(")") && s.length() >= 2) {
            s = s.substring(1, s.length()-1).trim();
        }
        if (s.length() >= 1) {
            char c0 = s.charAt(0);
            if (c0 == '"' || c0 == '\'' ||
                    (s.length() >= 2 && (s.startsWith("f\"") || s.startsWith("f'") ||
                            s.startsWith("r\"") || s.startsWith("r'") ||
                            s.startsWith("u\"") || s.startsWith("u'") ||
                            s.startsWith("b\"") || s.startsWith("b'")))) {
                return "builtins.str";
            }
        }
        if (s.startsWith("[")) return "builtins.list";
        if (s.startsWith("{")) return s.contains(":") ? "builtins.dict" : "builtins.set";
        if (s.startsWith("(") && s.endsWith(")")) return "builtins.tuple";

        if (s.startsWith("list("))  return "builtins.list";
        if (s.startsWith("dict("))  return "builtins.dict";
        if (s.startsWith("set("))   return "builtins.set";
        if (s.startsWith("tuple(")) return "builtins.tuple";
        if (s.startsWith("str("))   return "builtins.str";
        if (s.startsWith("bytes(")) return "builtins.bytes";
        return null;
    }

    /* ============================== Primary / attribute / calls ============================== */

    @Override public void exitPrimary(PythonParser.PrimaryContext ctx) {
        String text = ctx.getText();
        if (text == null || text.trim().isEmpty()) return;

        // Skip obvious strings/docstrings
        if (text.startsWith("\"\"\"") || text.startsWith("'''") ||
                text.startsWith("\"")    || text.startsWith("'")) return;

        if (INCLUDE_ATTRIBUTE_ACCESS && text.contains(".") && !text.contains("(")) {
            handleAttributeAccess(text);
        }
        if (text.contains("(")) {
            extractCallsFromExpression(text);
        }
    }

    private void handleAttributeAccess(String text) {
        String resolved = resolveDottedExpression(text);
        if (resolved == null || resolved.isEmpty()) return;
        List<String> parts = splitDottedNonEmpty(resolved);
        if (parts.size() < 2) return;

        String moduleName = parts.get(0);
        String attrName   = parts.get(1);
        if (MODULE_ATTRIBUTES.containsKey(moduleName) &&
                MODULE_ATTRIBUTES.get(moduleName).contains(attrName)) {
            addResult(resolved);
            logDebug("Module attribute: " + resolved);
        }
    }

    private void extractCallsFromExpression(String exprText) {
        if (exprText == null || exprText.trim().isEmpty()) return;
        if (exprText.contains("\"\"\"") || exprText.contains("'''") || exprText.startsWith("#")) return;

        int idx = 0, n = exprText.length();
        while (true) {
            int open = exprText.indexOf('(', idx);
            if (open < 0) break;

            // capture callee token left of '('
            int j = open - 1;
            while (j >= 0 && Character.isWhitespace(exprText.charAt(j))) j--;
            int end = j;
            while (j >= 0) {
                char c = exprText.charAt(j);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') j--;
                else break;
            }
            int start = j + 1;

            if (start <= end) {
                String rawCallee = exprText.substring(start, end + 1);
                if (!rawCallee.isEmpty()) {
                    rawCallee = stripLeadingGlue(rawCallee);
                    processFunctionCall(rawCallee, resolveDottedExpression(rawCallee));
                }
            }

            int k = skipParens(exprText, open);
            idx = Math.max(k, open + 1);
        }
    }

    private void processFunctionCall(String rawCallee, String resolved) {
        if (resolved == null || resolved.isEmpty()) return;

        List<String> parts = splitDottedNonEmpty(resolved);
        if (parts.isEmpty()) return;

        String last = parts.get(parts.size() - 1);
        String qualifier = (parts.size() > 1) ? String.join(".", parts.subList(0, parts.size() - 1)) : "";

        // If receiver looks like a single name (e.g., "path.read_text"), qualify via var type when available
        if (rawCallee.contains(".")) {
            List<String> rawParts = splitDottedNonEmpty(rawCallee);
            if (rawParts.size() >= 2) {
                String rawBase = rawParts.get(0);
                String varType = lookupVarType(rawBase);
                if (varType != null && !varType.isEmpty()) {
                    qualifier = varType;
                    last = rawParts.get(rawParts.size() - 1);
                } else {
                    // Fallback mapping for known classes (e.g., pathlib.Path)
                    String meth = rawParts.get(rawParts.size() - 1);
                    if (KNOWN_INSTANCE_METHODS.getOrDefault("pathlib.Path", Collections.emptySet()).contains(meth)) {
                        qualifier = "pathlib.Path";
                        last = meth;
                    }
                }
            }
        }

        // bare call
        if (qualifier.isEmpty()) {
            if (BUILTINS.contains(last)) {
                addResult("builtins." + last);
                return;
            }
            String viaAlias = aliasMap.get(last);
            if (viaAlias != null) {
                String tail = viaAlias.substring(viaAlias.lastIndexOf('.') + 1);
                if (isCamel(tail)) addResult(INCLUDE_INIT_SUFFIX ? viaAlias + ".__init__" : viaAlias);
                else addResult(viaAlias);
                return;
            }
            // Heuristic: obj() where obj has an inferred class type
            if (INCLUDE_CALL_SUFFIX && lookupVarType(last) != null) {
                addResult(lookupVarType(last) + ".__call__");
            }
            return;
        }

        // qualified call
        if (isCamel(last)) {
            addResult(INCLUDE_INIT_SUFFIX ? qualifier + "." + last + ".__init__" : qualifier + "." + last);
        } else {
            // normalize *.is_integer() if still untyped
            if ("is_integer".equals(last) && (lookupVarType(qualifier) == null) && !qualifier.contains(".")) {
                addResult("builtins.float.is_integer");
            } else {
                addResult(qualifier + "." + last);
            }
        }
    }

    private static int skipParens(String s, int open) {
        int n = s.length(), depth = 1, k = open + 1;
        while (k < n && depth > 0) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            k++;
        }
        return k;
    }

    /* ============================== Literals (optional) ============================== */

    @Override public void exitListcomp(PythonParser.ListcompContext ctx) {
        if (INCLUDE_LITERAL_TYPES) addResult("builtins.list");
        extractCallsFromExpression(ctx.getText());
    }
    @Override public void exitList (PythonParser.ListContext  ctx) { if (INCLUDE_LITERAL_TYPES) addResult("builtins.list"); }
    @Override public void exitDict (PythonParser.DictContext  ctx) { if (INCLUDE_LITERAL_TYPES) addResult("builtins.dict"); }
    @Override public void exitSet  (PythonParser.SetContext   ctx) { if (INCLUDE_LITERAL_TYPES) addResult("builtins.set"); }
    @Override public void exitTuple(PythonParser.TupleContext ctx) { if (INCLUDE_LITERAL_TYPES) addResult("builtins.tuple"); }

    /* ============================== String literal helpers ============================== */

    private void handleStringMethods(String expr) {
        Pattern p = Pattern.compile("([\"'][^\"']*[\"'])\\.join\\(");
        Matcher m = p.matcher(expr);
        while (m.find()) addResult("builtins.str.join");
    }

    @Override public void exitExpression(PythonParser.ExpressionContext ctx) {
        String text = ctx.getText();
        if (text != null && !text.trim().isEmpty()) {
            extractCallsFromExpression(text);
            handleStringMethods(text);
        }
    }
}
