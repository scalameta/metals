   package example;
//         ^^^^^^^ reference example/

   public class JavaOverloading {
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading# CLASS
//              ^^^^^^^^^^^^^^^ definition example/JavaOverloading#`<init>`(). CONSTRUCTOR
     public int name = 1;
//              ^^^^ definition example/JavaOverloading#name. FIELD

     public static int name(String name) {
//                     ^^^^ definition example/JavaOverloading#name(+2). METHOD
//                          ^^^^^^ reference String.
       return name.length();
//            ^^^^^^ reference length().
     }

     public int name() {
//              ^^^^ definition example/JavaOverloading#name(). METHOD
       return 1;
     }

     public int name(int n) {
//              ^^^^ definition example/JavaOverloading#name(+1). METHOD
       return n;
     }
   }