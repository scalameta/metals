   package example;
//         ^^^^^^^ reference example/

   public class JavaOverloading {
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading#
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading#`<init>`().
       public int name = 1;
//                ^^^^ definition example/JavaOverloading#name.
       public static int name(String name) { return name.length(); }
//                       ^^^^ definition example/JavaOverloading#name().
//                            ^^^^^^ reference java/lang/String#
//                                   ^^^^ definition local0
//                                                  ^^^^ reference local0
//                                                       ^^^^^^ reference java/lang/String#length().
       public int name() { return 1; }
//                ^^^^ definition example/JavaOverloading#name(+1).
       public int name(int n) { return n; }
//                ^^^^ definition example/JavaOverloading#name(+2).
//                         ^ definition local1
//                                     ^ reference local1
   }
