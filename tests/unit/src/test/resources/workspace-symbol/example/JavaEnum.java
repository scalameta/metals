package example;

public enum JavaEnum/*example.JavaEnum#*/ {
    A(1),
    B(2);


    JavaEnum(int d) {
        this.d = d;
    }

    public static void a/*example.JavaEnum#a().*/() {
    }

    public int b/*example.JavaEnum#b().*/() {
        return 1;
    }

    ;
    public static int c = 2;
    public int d = 2;

    public class C/*example.JavaEnum#C#*/ {
        public int b/*example.JavaEnum#C#b().*/() {
            return 1;
        }

        ;
        public int d = 2;
    }

    public static class F/*example.JavaEnum#F#*/ {
        public static void a/*example.JavaEnum#F#a().*/() {
        }

        public int b/*example.JavaEnum#F#b().*/() {
            return 1;
        }

        ;
        public static int c = 2;
        public int d = 2;
    }
}
