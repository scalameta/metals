   package example;
//         ^^^^^^^ reference example/

   import java.util.stream.Stream;
//        ^^^^ reference java/
//             ^^^^ reference java/util/
//                  ^^^^^^ reference java/util/stream/
//                         ^^^^^^ reference java/util/stream/Stream#

   class JavaMethodReference {
//       ^^^^^^^^^^^^^^^^^^^ definition example/JavaMethodReference#
//       ^^^^^^^^^^^^^^^^^^^ definition example/JavaMethodReference#`<init>`().
     public void method() {
//               ^^^^^^ definition example/JavaMethodReference#method().
       Stream.of("hello", "world").map(String::length).forEach(System.out::println);
//     ^^^^^^ reference java/util/stream/Stream#
//            ^^ reference java/util/stream/Stream#of(+1).
//                                 ^^^ reference java/util/stream/Stream#map().
//                                     ^^^^^^ reference java/lang/String#
//                                             ^^^^^^ reference java/lang/String#length().
//                                                     ^^^^^^^ reference java/util/stream/Stream#forEach().
//                                                             ^^^^^^ reference java/lang/System#
//                                                                    ^^^ reference java/lang/System#out.
//                                                                         ^^^^^^^ reference java/io/PrintStream#println(+9).
     }
   }
