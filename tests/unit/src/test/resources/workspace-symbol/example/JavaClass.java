package example;

public class JavaClass/*example.JavaClass#*/ {

    private JavaClass() {

    }
    public JavaClass(int d) {
        this.d = d;
    }

    public static void a/*example.JavaClass#a().*/() {
    }

    public int b/*example.JavaClass#b().*/() {
        return 1;
    }

    public static int c = 2;
    public int d = 2;

    public class InnerClass/*example.JavaClass#InnerClass#*/ {
        public int b/*example.JavaClass#InnerClass#b().*/() {
            return 1;
        }

        public int d = 2;
    }

    public static class InnerStaticClass/*example.JavaClass#InnerStaticClass#*/ {
        public static void a/*example.JavaClass#InnerStaticClass#a().*/() {
        }

        public int b/*example.JavaClass#InnerStaticClass#b().*/() {
            return 1;
        }

        public static int c = 2;
        public int d = 2;
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
