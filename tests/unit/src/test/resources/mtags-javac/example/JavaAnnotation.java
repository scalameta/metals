   package example;
//         ^^^^^^^ reference example/

   @Deprecated(since = "1.0", forRemoval = true)
//  ^^^^^^^^^^ reference Deprecated.
//             ^^^^^ reference since().
//                            ^^^^^^^^^^ reference forRemoval().
   class JavaAnnotation {}//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation# CLASS
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#`<init>`(). CONSTRUCTOR
