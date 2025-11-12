   package example;
//         ^^^^^^^ reference example/

   import java.io.IOException;
//        ^^ reference io.
//        ^^^^ reference java.
//        ^^^^^^^^^^^ reference IOException.

   public class JavaExtends {
//              ^^^^^^^^^^^ definition example/JavaExtends# CLASS
//              ^^^^^^^^^^^ definition example/JavaExtends#`<init>`(). CONSTRUCTOR

     enum Color implements AutoCloseable, Runnable {
//        ^^^^^ definition example/JavaExtends#Color# CLASS(enum)
//                         ^^^^^^^^^^^^^ reference AutoCloseable:
//                                        ^^^^^^^^ reference Runnable:
       RED("red"),
//     ^^^ definition example/JavaExtends#Color#RED. FIELD(enum)
       GREEN("green"),
//     ^^^^^ definition example/JavaExtends#Color#GREEN. FIELD(enum)
       BLUE("blue");
//     ^^^^ definition example/JavaExtends#Color#BLUE. FIELD(enum)

       private final String name;
//                   ^^^^^^ reference String.
//                          ^^^^ definition example/JavaExtends#Color#name. FIELD

       Color(String name) {
//     ^^^^^ definition example/JavaExtends#Color#`<init>`(). CONSTRUCTOR
//           ^^^^^^ reference String.
         this.name = name;
       }

       @Override
//     ^^^^^ reference close():
//      ^^^^^^^^ reference Override.
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#Color#close(). METHOD
//                                ^^^^^^^^^^^ reference IOException.
         System.out.println("Closing...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }

       @Override
//     ^^^ reference run():
//      ^^^^^^^^ reference Override.
       public void run() {
//                 ^^^ definition example/JavaExtends#Color#run(). METHOD
         System.out.println("Running...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }

     interface MyInterface extends AutoCloseable {
//             ^^^^^^^^^^^ definition example/JavaExtends#MyInterface# INTERFACE
//                                 ^^^^^^^^^^^^^ reference AutoCloseable:
       void myMethod();
//          ^^^^^^^^ definition example/JavaExtends#MyInterface#myMethod(). METHOD
     }

     record MyRecord(String name) implements MyInterface, Runnable {
//          ^^^^^^^^ definition example/JavaExtends#MyRecord# CLASS
//          ^^^^^^^^ definition example/JavaExtends#MyRecord#`<init>`(). CONSTRUCTOR
//                   ^^^^^^ reference String.
//                          ^^^^ definition example/JavaExtends#MyRecord#name(). METHOD
//                                           ^^^^^^^^^^^ reference MyInterface:
//                                                        ^^^^^^^^ reference Runnable:
       @Override
//     ^^^^^ reference close():
//      ^^^^^^^^ reference Override.
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#MyRecord#close(). METHOD
//                                ^^^^^^^^^^^ reference IOException.
         System.out.println("Closing...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }

       @Override
//     ^^^^^^^^ reference myMethod():
//      ^^^^^^^^ reference Override.
       public void myMethod() {
//                 ^^^^^^^^ definition example/JavaExtends#MyRecord#myMethod(). METHOD
         System.out.println("MyMethod");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }

       @Override
//     ^^^ reference run():
//      ^^^^^^^^ reference Override.
       public void run() {
//                 ^^^ definition example/JavaExtends#MyRecord#run(). METHOD
         System.out.println("Running...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }

     class MyClass extends Exception implements MyInterface, Runnable {
//         ^^^^^^^ definition example/JavaExtends#MyClass# CLASS
//         ^^^^^^^ definition example/JavaExtends#MyClass#`<init>`(). CONSTRUCTOR
//                         ^^^^^^^^^ reference Exception:
//                                              ^^^^^^^^^^^ reference MyInterface:
//                                                           ^^^^^^^^ reference Runnable:

       @Override
//     ^^^^^ reference close():
//      ^^^^^^^^ reference Override.
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#MyClass#close(). METHOD
//                                ^^^^^^^^^^^ reference IOException.
         System.out.println("Closing...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }

       @Override
//     ^^^^^^^^ reference myMethod():
//      ^^^^^^^^ reference Override.
       public void myMethod() {
//                 ^^^^^^^^ definition example/JavaExtends#MyClass#myMethod(). METHOD
         System.out.println("MyMethod");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }

       @Override
//     ^^^^^^^^^^ reference getMessage():
//      ^^^^^^^^ reference Override.
       public String getMessage() {
//            ^^^^^^ reference String.
//                   ^^^^^^^^^^ definition example/JavaExtends#MyClass#getMessage(). METHOD
         return "MyClass.getMessage()";
       }

       @Override
//     ^^^^^^^^^^^^^^^^ reference fillInStackTrace():
//      ^^^^^^^^ reference Override.
       public synchronized Throwable fillInStackTrace() {
//                         ^^^^^^^^^ reference Throwable.
//                                   ^^^^^^^^^^^^^^^^ definition example/JavaExtends#MyClass#fillInStackTrace(). METHOD
         return this;
       }

       @Override
//     ^^^ reference run():
//      ^^^^^^^^ reference Override.
       public void run() {
//                 ^^^ definition example/JavaExtends#MyClass#run(). METHOD
         System.out.println("Running...");
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }
   }