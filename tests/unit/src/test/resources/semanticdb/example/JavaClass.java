   package example;
//         ^^^^^^^ reference example/

   public class JavaClass {
//              ^^^^^^^^^ definition example/JavaClass#

     private JavaClass() {}
//           ^^^^^^^^^ definition example/JavaClass#`<init>`().

     public JavaClass(int d) {
//          ^^^^^^^^^ definition example/JavaClass#`<init>`(+1).
//                        ^ definition local0
       this.d = d;
//          ^ reference example/JavaClass#d.
//              ^ reference local0
     }

     public static void a() {
//                      ^ definition example/JavaClass#a().
       new JavaClass(42);
//         ^^^^^^^^^ reference example/JavaClass#`<init>`(+1).
     }

     public int b() {
//              ^ definition example/JavaClass#b().
       return 1;
     }

     public static int c = 2;
//                     ^ definition example/JavaClass#c.
     public int d = 2;
//              ^ definition example/JavaClass#d.

     public class InnerClass {
//                ^^^^^^^^^^ definition example/JavaClass#InnerClass#
//                ^^^^^^^^^^ definition example/JavaClass#InnerClass#`<init>`().
       public int b() {
//                ^ definition example/JavaClass#InnerClass#b().
         return 1;
       }

       public int d = 2;
//                ^ definition example/JavaClass#InnerClass#d.
     }

     public static class InnerStaticClass {
//                       ^^^^^^^^^^^^^^^^ definition example/JavaClass#InnerStaticClass#
//                       ^^^^^^^^^^^^^^^^ definition example/JavaClass#InnerStaticClass#`<init>`().
       public static void a() {}
//                        ^ definition example/JavaClass#InnerStaticClass#a().

       public int b() {
//                ^ definition example/JavaClass#InnerStaticClass#b().
         return 1;
       }

       public static int c = 2;
//                       ^ definition example/JavaClass#InnerStaticClass#c.
       public int d = 2;
//                ^ definition example/JavaClass#InnerStaticClass#d.
     }

     public interface InnerInterface {
//                    ^^^^^^^^^^^^^^ definition example/JavaClass#InnerInterface#
       public static void a() {}
//                        ^ definition example/JavaClass#InnerInterface#a().

       public int b();
//                ^ definition example/JavaClass#InnerInterface#b().
     }

     public String publicName() {
//          ^^^^^^ reference java/lang/String#
//                 ^^^^^^^^^^ definition example/JavaClass#publicName().
       return "name";
     }

     // Weird formatting
     @Override
//    ^^^^^^^^ reference java/lang/Override#
     public String toString() {
//          ^^^^^^ reference java/lang/String#
//                 ^^^^^^^^ definition example/JavaClass#toString().
       return "";
     }
   }
