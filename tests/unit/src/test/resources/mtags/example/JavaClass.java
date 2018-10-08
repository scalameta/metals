package example;

public class JavaClass/*example.JavaClass#*/ {

    public JavaClass/*example.JavaClass#`<init>`().*/(int d) {
        this.d = d;
    }

    public static void a/*example.JavaClass#a().*/() {
    }

    public int b/*example.JavaClass#b().*/() {
        return 1;
    }

    public static int c/*example.JavaClass#c.*/ = 2;
    public int d/*example.JavaClass#d.*/ = 2;

    public class InnerClass/*example.JavaClass#InnerClass#*/ {
        public int b/*example.JavaClass#InnerClass#b().*/() {
            return 1;
        }

        public int d/*example.JavaClass#InnerClass#d.*/ = 2;
    }

    public static class InnerStaticClass/*example.JavaClass#InnerStaticClass#*/ {
        public static void a/*example.JavaClass#InnerStaticClass#a().*/() {
        }

        public int b/*example.JavaClass#InnerStaticClass#b().*/() {
            return 1;
        }

        public static int c/*example.JavaClass#InnerStaticClass#c.*/ = 2;
        public int d/*example.JavaClass#InnerStaticClass#d.*/ = 2;
    }

    public interface InnerInterface/*example.JavaClass#InnerInterface#*/ {
        public static void a/*example.JavaClass#InnerInterface#a().*/() {
        }

        public int b/*example.JavaClass#InnerInterface#b().*/();
    }

    public String publicName/*example.JavaClass#publicName().*/() {
        return "name";
    }

    // Weird formatting
    @Override
    public String
    toString/*example.JavaClass#toString().*/() {
        return "";
    }
}
