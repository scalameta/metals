package example;

public class JavaOverloading {
    public int name = 1;
    public static int name(String name) { return name.length(); }
    public int name() { return 1; }
    public int name(int n) { return n; }
}
