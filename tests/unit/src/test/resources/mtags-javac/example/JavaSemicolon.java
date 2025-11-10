   package example;
//         ^^^^^^^ reference example/

   public class JavaSemicolon {
//              ^^^^^^^^^^^^^ definition example/JavaSemicolon# CLASS
//              ^^^^^^^^^^^^^ definition example/JavaSemicolon#`<init>`(). CONSTRUCTOR
     public static void a() {}
//                      ^ definition example/JavaSemicolon#a(). METHOD

     public int b() {
//              ^ definition example/JavaSemicolon#b(). METHOD
       return 1;
     }
     ;

     public static int c = 2;
//                     ^ definition example/JavaSemicolon#c. FIELD

     public class C {
//                ^ definition example/JavaSemicolon#C# CLASS
//                ^ definition example/JavaSemicolon#C#`<init>`(). CONSTRUCTOR
       public int b() {
//                ^ definition example/JavaSemicolon#C#b(). METHOD
         return 1;
       }
       ;

       public int d = 2;
//                ^ definition example/JavaSemicolon#C#d. FIELD
     }

     public static class F {
//                       ^ definition example/JavaSemicolon#F# CLASS
//                       ^ definition example/JavaSemicolon#F#`<init>`(). CONSTRUCTOR
       public static void a() {}
//                        ^ definition example/JavaSemicolon#F#a(). METHOD

       public int b() {
//                ^ definition example/JavaSemicolon#F#b(). METHOD
         return 1;
       }
       ;

       public static int c = 2;
//                       ^ definition example/JavaSemicolon#F#c. FIELD
       public int d = 2;
//                ^ definition example/JavaSemicolon#F#d. FIELD
     }
   }