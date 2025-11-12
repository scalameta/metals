   package example;
//         ^^^^^^^ reference example/

   public class JavaClass {
//              ^^^^^^^^^ definition example/JavaClass# CLASS

     private JavaClass() {}
//           ^^^^^^^^^ definition example/JavaClass#`<init>`(). CONSTRUCTOR

     public JavaClass(int d) {
//          ^^^^^^^^^ definition example/JavaClass#`<init>`(+1). CONSTRUCTOR
       this.d = d;
     }

     public static void a() {
//                      ^ definition example/JavaClass#a(). METHOD
       new JavaClass(42);
//         ^^^^^^^^^ reference JavaClass.
     }

     public int b() {
//              ^ definition example/JavaClass#b(). METHOD
       return 1;
     }

     public static int c = 2;
//                     ^ definition example/JavaClass#c. FIELD
     public int d = 2;
//              ^ definition example/JavaClass#d. FIELD

     public class InnerClass {
//                ^^^^^^^^^^ definition example/JavaClass#InnerClass# CLASS
//                ^^^^^^^^^^ definition example/JavaClass#InnerClass#`<init>`(). CONSTRUCTOR
       public int b() {
//                ^ definition example/JavaClass#InnerClass#b(). METHOD
         return 1;
       }

       public int d = 2;
//                ^ definition example/JavaClass#InnerClass#d. FIELD
     }

     public static class InnerStaticClass {
//                       ^^^^^^^^^^^^^^^^ definition example/JavaClass#InnerStaticClass# CLASS
//                       ^^^^^^^^^^^^^^^^ definition example/JavaClass#InnerStaticClass#`<init>`(). CONSTRUCTOR
       public static void a() {}
//                        ^ definition example/JavaClass#InnerStaticClass#a(). METHOD

       public int b() {
//                ^ definition example/JavaClass#InnerStaticClass#b(). METHOD
         return 1;
       }

       public static int c = 2;
//                       ^ definition example/JavaClass#InnerStaticClass#c. FIELD
       public int d = 2;
//                ^ definition example/JavaClass#InnerStaticClass#d. FIELD
     }

     public interface InnerInterface {
//                    ^^^^^^^^^^^^^^ definition example/JavaClass#InnerInterface# INTERFACE
       public static void a() {}
//                        ^ definition example/JavaClass#InnerInterface#a(). METHOD

       public int b();
//                ^ definition example/JavaClass#InnerInterface#b(). METHOD
     }

     public String publicName() {
//          ^^^^^^ reference String.
//                 ^^^^^^^^^^ definition example/JavaClass#publicName(). METHOD
       return "name";
     }

     // Weird formatting
     @Override
//   ^^^^^^^^ reference toString():
//    ^^^^^^^^ reference Override.
     public String toString() {
//          ^^^^^^ reference String.
//                 ^^^^^^^^ definition example/JavaClass#toString(). METHOD
       return "";
     }
   }