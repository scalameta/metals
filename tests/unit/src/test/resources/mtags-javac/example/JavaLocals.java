   package example;
//         ^^^^^^^ reference example/

   import java.util.Scanner;
//        ^^^^ reference java.
//        ^^^^ reference util.
//        ^^^^^^^ reference Scanner.
   import java.util.stream.Stream;
//        ^^^^ reference java.
//        ^^^^ reference util.
//        ^^^^^^ reference stream.
//        ^^^^^^ reference Stream.

   public class JavaLocals {
//              ^^^^^^^^^^ definition example/JavaLocals# CLASS
//              ^^^^^^^^^^ definition example/JavaLocals#`<init>`(). CONSTRUCTOR
     record Point(int x, int y) {}
//          ^^^^^ definition example/JavaLocals#Point# CLASS
//          ^^^^^ definition example/JavaLocals#Point#`<init>`(). CONSTRUCTOR
//                    ^ definition example/JavaLocals#Point#x(). METHOD
//                           ^ definition example/JavaLocals#Point#y(). METHOD

     public int test(int x) {
//              ^^^^ definition example/JavaLocals#test(). METHOD
       int a = 1;
       int b = x;
       if (b > 0) {
         var c = a + b;
         return c;
       }
       Object p = new Point(1, 2);
//     ^^^^^^ reference Object.
//                    ^^^^^ reference Point.
       // TODO: instanceof binding not handled yet
       if (p instanceof Point p2) {
//                      ^^^^^ reference Point.
         a += p2.x();
//            ^^ reference p2.
         a += p2.y();
//            ^ reference y().
//            ^^ reference p2.
       }
       try {
         for (int i = 0; i < 10; i++) {
           a += i;
         }
       } catch (Exception e) {
//              ^^^^^^^^^ reference Exception.
         a += e.getMessage().length();
//            ^^^^^^ reference length().
//            ^^^^^^^^^^ reference getMessage().
       }
       for (int e : new int[] {1, 2, 3}) {
         a += e;
       }
       try (var s = new Scanner(System.in)) {
//                      ^^^^^^^ reference Scanner.
//                              ^^ reference in.
//                              ^^^^^^ reference System.
         a += s.nextInt();
//            ^^^^^^^ reference nextInt().
       }
       return Stream.of(a, b).map(i -> i * 2).reduce(0, (i, j) -> i + j);
//            ^^ reference of().
//            ^^^ reference map().
//            ^^^^^^ reference reduce().
//            ^^^^^^ reference Stream.
     }
   }