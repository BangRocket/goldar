package dev.goldar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Short JSON builders. */
public final class J {
    private J() {}

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    public static void put(JsonObject o, String k, Object v) {
        if (v == null) return;
        if (v instanceof JsonElement e) o.add(k, e);
        else if (v instanceof Number n) o.addProperty(k, n);
        else if (v instanceof Boolean b) o.addProperty(k, b);
        else o.addProperty(k, v.toString());
    }

    public static double round(double v) { return Math.round(v * 10) / 10.0; }

    public static JsonArray arr(Iterable<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }
}
