   package example;
//         ^^^^^^^ reference example/

   @Deprecated(since = "1.0", forRemoval = true)
//  ^^^^^^^^^^ reference java/lang/Deprecated#
//             ^^^^^ reference java/lang/Deprecated#since().
//                            ^^^^^^^^^^ reference java/lang/Deprecated#forRemoval().
   class JavaAnnotation {}
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#`<init>`().
