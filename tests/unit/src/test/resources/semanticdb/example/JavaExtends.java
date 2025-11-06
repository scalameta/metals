   package example;
//         ^^^^^^^ reference example/

   import java.io.IOException;
//        ^^^^ reference java/
//             ^^ reference java/io/
//                ^^^^^^^^^^^ reference java/io/IOException#

   public class JavaExtends {
//              ^^^^^^^^^^^ definition example/JavaExtends#
//              ^^^^^^^^^^^ definition example/JavaExtends#`<init>`().

     enum Color implements AutoCloseable, Runnable {
//        ^^^^^ definition example/JavaExtends#Color#
//                         ^^^^^^^^^^^^^ reference java/lang/AutoCloseable#
//                                        ^^^^^^^^ reference java/lang/Runnable#
       RED("red"),
//     ^^^ definition example/JavaExtends#Color#RED.
//     ^^^ reference example/JavaExtends#Color#`<init>`().
       GREEN("green"),
//     ^^^^^ definition example/JavaExtends#Color#GREEN.
//     ^^^^^ reference example/JavaExtends#Color#`<init>`().
       BLUE("blue");
//     ^^^^ definition example/JavaExtends#Color#BLUE.
//     ^^^^ reference example/JavaExtends#Color#`<init>`().

       private final String name;
//                   ^^^^^^ reference java/lang/String#
//                          ^^^^ definition example/JavaExtends#Color#name.

       Color(String name) {
//     ^^^^^ definition example/JavaExtends#Color#`<init>`().
//           ^^^^^^ reference java/lang/String#
//                  ^^^^ definition local0
         this.name = name;
//            ^^^^ reference example/JavaExtends#Color#name.
//                   ^^^^ reference local0
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#Color#close().
//                                ^^^^^^^^^^^ reference java/io/IOException#
         System.out.println("Closing...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void run() {
//                 ^^^ definition example/JavaExtends#Color#run().
         System.out.println("Running...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }
     }

     interface MyInterface extends AutoCloseable {
//             ^^^^^^^^^^^ definition example/JavaExtends#MyInterface#
//                                 ^^^^^^^^^^^^^ reference java/lang/AutoCloseable#
       void myMethod();
//          ^^^^^^^^ definition example/JavaExtends#MyInterface#myMethod().
     }

     record MyRecord(String name) implements MyInterface, Runnable {
//          ^^^^^^^^ definition example/JavaExtends#MyRecord#
//          ^^^^^^^^ definition example/JavaExtends#MyRecord#`<init>`().
//                   ^^^^^^ reference java/lang/String#
//                          ^^^^ definition local1
//                          ^^^^ definition example/JavaExtends#MyRecord#name().
//                                           ^^^^^^^^^^^ reference example/JavaExtends#MyInterface#
//                                                        ^^^^^^^^ reference java/lang/Runnable#
       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#MyRecord#close().
//                                ^^^^^^^^^^^ reference java/io/IOException#
         System.out.println("Closing...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void myMethod() {
//                 ^^^^^^^^ definition example/JavaExtends#MyRecord#myMethod().
         System.out.println("MyMethod");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void run() {
//                 ^^^ definition example/JavaExtends#MyRecord#run().
         System.out.println("Running...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }
     }

     class MyClass extends Exception implements MyInterface, Runnable {
//         ^^^^^^^ definition example/JavaExtends#MyClass#
//         ^^^^^^^ definition example/JavaExtends#MyClass#`<init>`().
//                         ^^^^^^^^^ reference java/lang/Exception#
//                                              ^^^^^^^^^^^ reference example/JavaExtends#MyInterface#
//                                                           ^^^^^^^^ reference java/lang/Runnable#

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void close() throws IOException {
//                 ^^^^^ definition example/JavaExtends#MyClass#close().
//                                ^^^^^^^^^^^ reference java/io/IOException#
         System.out.println("Closing...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void myMethod() {
//                 ^^^^^^^^ definition example/JavaExtends#MyClass#myMethod().
         System.out.println("MyMethod");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public String getMessage() {
//            ^^^^^^ reference java/lang/String#
//                   ^^^^^^^^^^ definition example/JavaExtends#MyClass#getMessage().
         return "MyClass.getMessage()";
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public synchronized Throwable fillInStackTrace() {
//                         ^^^^^^^^^ reference java/lang/Throwable#
//                                   ^^^^^^^^^^^^^^^^ definition example/JavaExtends#MyClass#fillInStackTrace().
         return this;
       }

       @Override
//      ^^^^^^^^ reference java/lang/Override#
       public void run() {
//                 ^^^ definition example/JavaExtends#MyClass#run().
         System.out.println("Running...");
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
       }
     }
   }
