package example;

public class JavaOverloading/*example.JavaOverloading#*/ {
    public int name/*example.JavaOverloading#name.*/ = 1;
    public static int name/*example.JavaOverloading#name(+2).*/(String name) { return name.length(); }
    public int name/*example.JavaOverloading#name().*/() { return 1; }
    public int name/*example.JavaOverloading#name(+1).*/(int n) { return n; }
}
