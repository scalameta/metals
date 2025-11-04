   package example;
//         ^^^^^^^ reference example/

   public class JavaSemicolon {
//              ^^^^^^^^^^^^^ definition example/JavaSemicolon#
//              ^^^^^^^^^^^^^ definition example/JavaSemicolon#`<init>`().
     public static void a() {}
//                      ^ definition example/JavaSemicolon#a().

     public int b() {
//              ^ definition example/JavaSemicolon#b().
       return 1;
     }
     ;

     public static int c = 2;
//                     ^ definition example/JavaSemicolon#c.

     public class C {
//                ^ definition example/JavaSemicolon#C#
//                ^ definition example/JavaSemicolon#C#`<init>`().
       public int b() {
//                ^ definition example/JavaSemicolon#C#b().
         return 1;
       }
       ;

       public int d = 2;
//                ^ definition example/JavaSemicolon#C#d.
     }

     public static class F {
//                       ^ definition example/JavaSemicolon#F#
//                       ^ definition example/JavaSemicolon#F#`<init>`().
       public static void a() {}
//                        ^ definition example/JavaSemicolon#F#a().

       public int b() {
//                ^ definition example/JavaSemicolon#F#b().
         return 1;
       }
       ;

       public static int c = 2;
//                       ^ definition example/JavaSemicolon#F#c.
       public int d = 2;
//                ^ definition example/JavaSemicolon#F#d.
     }
   }
