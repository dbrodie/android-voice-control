package android.util;

/**
 * JVM unit-test stand-in for android.util.Log (android.jar's version throws "not mocked"). Test classes come
 * first on the unit-test classpath, so code under test that logs through VcLog prints to stdout instead.
 */
public final class Log {
    private Log() {}

    private static int print(String level, String tag, String msg, Throwable t) {
        System.out.println(level + "/" + tag + ": " + msg + (t != null ? " (" + t + ")" : ""));
        return 0;
    }

    public static int v(String tag, String msg) { return print("V", tag, msg, null); }
    public static int d(String tag, String msg) { return print("D", tag, msg, null); }
    public static int i(String tag, String msg) { return print("I", tag, msg, null); }
    public static int w(String tag, String msg) { return print("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return print("W", tag, msg, t); }
    public static int e(String tag, String msg) { return print("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return print("E", tag, msg, t); }
}
