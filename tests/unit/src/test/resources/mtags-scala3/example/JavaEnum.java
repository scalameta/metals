package example;

public enum JavaEnum/*example.JavaEnum#*/ {
    A/*example.JavaEnum#A.*/(1),
    B/*example.JavaEnum#B.*/(2);


    JavaEnum/*example.JavaEnum#`<init>`().*/(int d) {
        this.d = d;
    }

    public static void a/*example.JavaEnum#a().*/() {
    }

    public int b/*example.JavaEnum#b().*/() {
        return 1;
    }

    ;
    public static int c/*example.JavaEnum#c.*/ = 2;
    public int d/*example.JavaEnum#d.*/ = 2;

    public class C/*example.JavaEnum#C#*/ {
        public int b/*example.JavaEnum#C#b().*/() {
            return 1;
        }

        ;
        public int d/*example.JavaEnum#C#d.*/ = 2;
    }

    public static class F/*example.JavaEnum#F#*/ {
        public static void a/*example.JavaEnum#F#a().*/() {
        }

        public int b/*example.JavaEnum#F#b().*/() {
            return 1;
        }

        ;
        public static int c/*example.JavaEnum#F#c.*/ = 2;
        public int d/*example.JavaEnum#F#d.*/ = 2;
    }
}
