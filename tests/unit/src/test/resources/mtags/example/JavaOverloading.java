package example;

public class JavaOverloading/*example.JavaOverloading#*/ {
    public int name/*example.JavaOverloading#name.*/ = 1;
    public static int name/*example.JavaOverloading#name().*/(String name) { return name.length(); }
    public int name/*example.JavaOverloading#name(+1).*/() { return 1; }
    public int name/*example.JavaOverloading#name(+2).*/(int n) { return n; }
}
