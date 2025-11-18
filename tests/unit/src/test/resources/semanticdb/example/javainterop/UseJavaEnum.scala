   package example.javainterop
//         ^^^^^^^ reference example/
//                 ^^^^^^^^^^^ reference example/javainterop/

   import example.JavaEnum
//        ^^^^^^^ reference example/
//                ^^^^^^^^ reference example/JavaEnum#

   object UseJavaEnum {
//        ^^^^^^^^^^^ definition example/javainterop/UseJavaEnum.
     def main(): Unit = {
//       ^^^^ definition example/javainterop/UseJavaEnum.main().
//               ^^^^ reference scala/Unit#
       println(JavaEnum.A)
//     ^^^^^^^ reference scala/Predef.println(+1).
//             ^^^^^^^^ reference example/JavaEnum#
//                      ^ reference example/JavaEnum#A.
     }
   }