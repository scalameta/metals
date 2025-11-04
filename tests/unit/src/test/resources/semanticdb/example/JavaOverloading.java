   package example;
//         ^^^^^^^ reference example/

   public class JavaOverloading {
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading#
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading#`<init>`().
     public int name = 1;
//              ^^^^ definition example/JavaOverloading#name.

     public static int name(String name) {
//                     ^^^^ definition example/JavaOverloading#name().
//                          ^^^^^^ reference java/lang/String#
//                                 ^^^^ definition local0
       return name.length();
//            ^^^^ reference local0
//                 ^^^^^^ reference java/lang/String#length().
     }

     public int name() {
//              ^^^^ definition example/JavaOverloading#name(+1).
       return 1;
     }

     public int name(int n) {
//              ^^^^ definition example/JavaOverloading#name(+2).
//                       ^ definition local1
       return n;
//            ^ reference local1
     }
   }
