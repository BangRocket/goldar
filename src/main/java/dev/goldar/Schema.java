package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks a value against a JSON Schema and words each fault the way Geldar's validator does:
 * "&lt;path&gt; &lt;message&gt;", with "" for the root. Covers the keywords the tool schemas use.
 */
public final class Schema {
    private final JsonObject root;
    private final List<String> errors = new ArrayList<>();

    private Schema(JsonObject root) { this.root = root; }

    /** Every fault in the value, in the order Geldar reports them. Empty when the value is valid. */
    public static List<String> check(JsonObject schema, JsonElement value) {
        Schema s = new Schema(schema);
        s.visit(schema, value, "");
        return s.errors;
    }

    private void fail(String path, String message) {
        errors.add((path.isEmpty() ? "\"\"" : path) + " " + message);
    }

    private JsonObject resolve(String ref) {
        JsonElement at = root;
        for (String part : ref.substring(ref.indexOf('#') + 1).split("/")) {
            if (part.isEmpty()) continue;
            at = at.getAsJsonObject().get(part.replace("~1", "/").replace("~0", "~"));
        }
        return at.getAsJsonObject();
    }

    private static boolean isInteger(JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return false;
        try {
            return v.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean is(String type, JsonElement v) {
        return switch (type) {
            case "object" -> v.isJsonObject();
            case "array" -> v.isJsonArray();
            case "null" -> v.isJsonNull();
            case "string" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
            case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
            case "number" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
            case "integer" -> isInteger(v);
            default -> false;
        };
    }

    /** Equal as JSON: numbers by value, so 1 and 1.0 are the same. */
    static boolean same(JsonElement a, JsonElement b) {
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive x = a.getAsJsonPrimitive(), y = b.getAsJsonPrimitive();
            if (x.isNumber() && y.isNumber()) return x.getAsBigDecimal().compareTo(y.getAsBigDecimal()) == 0;
            return x.isNumber() == y.isNumber() && x.isString() == y.isString() && x.equals(y);
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
            if (x.size() != y.size()) return false;
            for (Map.Entry<String, JsonElement> e : x.entrySet()) if (!y.has(e.getKey()) || !same(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        return a.isJsonNull() && b.isJsonNull();
    }

    private static String number(JsonElement n) {
        BigDecimal d = n.getAsBigDecimal().stripTrailingZeros();
        return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
    }

    private static String pointer(String path, String key) {
        return path + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private void visit(JsonObject s, JsonElement v, String path) {
        if (s.has("$ref")) visit(resolve(s.get("$ref").getAsString()), v, path);

        if (s.has("type")) {
            JsonElement t = s.get("type");
            boolean ok = false;
            String names;
            if (t.isJsonArray()) {
                List<String> all = new ArrayList<>();
                for (JsonElement one : t.getAsJsonArray()) { all.add(one.getAsString()); ok |= is(one.getAsString(), v); }
                names = String.join(",", all);
            } else {
                names = t.getAsString();
                ok = is(names, v);
            }
            if (!ok) fail(path, "must be " + names);
        }
        if (s.has("const") && !same(s.get("const"), v)) fail(path, "must be equal to constant");
        if (s.has("enum")) {
            boolean found = false;
            for (JsonElement e : s.getAsJsonArray("enum")) found |= same(e, v);
            if (!found) fail(path, "must be equal to one of the allowed values");
        }
        if (s.has("anyOf")) {
            int before = errors.size();
            boolean any = false;
            for (JsonElement branch : s.getAsJsonArray("anyOf")) {
                int at = errors.size();
                visit(branch.getAsJsonObject(), v, path);
                any |= errors.size() == at;
            }
            if (any) errors.subList(before, errors.size()).clear();
            else fail(path, "must match a schema in anyOf");
        }
        if (s.has("oneOf")) {
            int before = errors.size();
            int passed = 0;
            for (JsonElement branch : s.getAsJsonArray("oneOf")) {
                int at = errors.size();
                visit(branch.getAsJsonObject(), v, path);
                if (errors.size() == at) passed++;
            }
            if (passed == 1) errors.subList(before, errors.size()).clear();
            else fail(path, "must match exactly one schema in oneOf");
        }

        if (is("number", v)) {
            BigDecimal n = v.getAsBigDecimal();
            if (s.has("maximum") && n.compareTo(s.get("maximum").getAsBigDecimal()) > 0) fail(path, "must be <= " + number(s.get("maximum")));
            if (s.has("minimum") && n.compareTo(s.get("minimum").getAsBigDecimal()) < 0) fail(path, "must be >= " + number(s.get("minimum")));
        }
        if (is("string", v)) {
            String text = v.getAsString();
            int length = text.codePointCount(0, text.length());
            if (s.has("maxLength") && length > s.get("maxLength").getAsInt()) fail(path, "must NOT have more than " + s.get("maxLength").getAsInt() + " characters");
            if (s.has("minLength") && length < s.get("minLength").getAsInt()) fail(path, "must NOT have fewer than " + s.get("minLength").getAsInt() + " characters");
            if (s.has("pattern") && !Pattern.compile(s.get("pattern").getAsString()).matcher(text).find()) {
                fail(path, "must match pattern \"" + s.get("pattern").getAsString() + "\"");
            }
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            if (s.has("maxItems") && a.size() > s.get("maxItems").getAsInt()) fail(path, "must NOT have more than " + s.get("maxItems").getAsInt() + " items");
            if (s.has("minItems") && a.size() < s.get("minItems").getAsInt()) fail(path, "must NOT have fewer than " + s.get("minItems").getAsInt() + " items");
            if (s.has("uniqueItems") && s.get("uniqueItems").getAsBoolean()) {
                outer:
                for (int i = 1; i < a.size(); i++) {
                    for (int j = 0; j < i; j++) {
                        if (same(a.get(i), a.get(j))) {
                            fail(path, "must NOT have duplicate items (items ## " + j + " and " + i + " are identical)");
                            break outer;
                        }
                    }
                }
            }
            if (s.has("items") && s.get("items").isJsonObject()) {
                for (int i = 0; i < a.size(); i++) visit(s.getAsJsonObject("items"), a.get(i), path + "/" + i);
            }
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            if (s.has("required")) {
                for (JsonElement r : s.getAsJsonArray("required")) {
                    if (!o.has(r.getAsString())) fail(path, "must have required property '" + r.getAsString() + "'");
                }
            }
            JsonObject props = s.has("properties") ? s.getAsJsonObject("properties") : new JsonObject();
            if (s.has("additionalProperties")) {
                JsonElement extra = s.get("additionalProperties");
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (props.has(e.getKey())) continue;
                    if (extra.isJsonObject()) visit(extra.getAsJsonObject(), e.getValue(), pointer(path, e.getKey()));
                    else if (!extra.getAsBoolean()) fail(path, "must NOT have additional properties '" + e.getKey() + "'");
                }
            }
            for (Map.Entry<String, JsonElement> p : props.entrySet()) {
                if (o.has(p.getKey()) && p.getValue().isJsonObject()) visit(p.getValue().getAsJsonObject(), o.get(p.getKey()), pointer(path, p.getKey()));
            }
        }
    }
}
