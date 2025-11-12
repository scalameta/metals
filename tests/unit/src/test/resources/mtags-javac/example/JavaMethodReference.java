   package example;
//         ^^^^^^^ reference example/

   import java.util.stream.Stream;
//        ^^^^ reference java.
//        ^^^^ reference util.
//        ^^^^^^ reference stream.
//        ^^^^^^ reference Stream.

   class JavaMethodReference {
//       ^^^^^^^^^^^^^^^^^^^ definition example/JavaMethodReference# CLASS
//       ^^^^^^^^^^^^^^^^^^^ definition example/JavaMethodReference#`<init>`(). CONSTRUCTOR
     public void method() {
//               ^^^^^^ definition example/JavaMethodReference#method(). METHOD
       Stream.of("hello", "world").map(String::length).forEach(System.out::println);
//     ^^ reference of().
//     ^^^ reference map().
//     ^^^^^^ reference Stream.
//     ^^^^^^^ reference forEach().
//                                     ^^^^^^ reference String.
//                                     ^^^^^^ reference length().
//                                                             ^^^ reference out.
//                                                             ^^^^^^ reference System.
//                                                             ^^^^^^^ reference println().
     }
   }