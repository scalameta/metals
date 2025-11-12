   package example;
//         ^^^^^^^ reference example/

   import java.lang.annotation.ElementType;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^^^^^^ reference java/lang/annotation/ElementType#
   import java.lang.annotation.Target;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^ reference java/lang/annotation/Target#

   @Deprecated(since = "1.0", forRemoval = true)
//  ^^^^^^^^^^ reference java/lang/Deprecated#
//             ^^^^^ reference java/lang/Deprecated#since().
//                            ^^^^^^^^^^ reference java/lang/Deprecated#forRemoval().
   class JavaAnnotation {
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#`<init>`().

     @Target({ElementType.TYPE})
//    ^^^^^^ reference java/lang/annotation/Target#
//            ^^^^^^^^^^^ reference java/lang/annotation/ElementType#
//                        ^^^^ reference java/lang/annotation/ElementType#TYPE.
     @interface MyAnnotation {
//              ^^^^^^^^^^^^ definition example/JavaAnnotation#MyAnnotation#
       String[] values() default {"default"};
//     ^^^^^^ reference java/lang/String#
//              ^^^^^^ definition example/JavaAnnotation#MyAnnotation#values().
     }

     public static final String CONSTANT = "constant";
//                       ^^^^^^ reference java/lang/String#
//                              ^^^^^^^^ definition example/JavaAnnotation#CONSTANT.

     @MyAnnotation(values = {CONSTANT, "value2"})
//    ^^^^^^^^^^^^ reference example/JavaAnnotation#MyAnnotation#
//                 ^^^^^^ reference example/JavaAnnotation#MyAnnotation#values().
//                           ^^^^^^^^ reference example/JavaAnnotation#CONSTANT.
     class MyClass<T> {
//         ^^^^^^^ definition example/JavaAnnotation#MyClass#
//         ^^^^^^^ definition example/JavaAnnotation#MyClass#`<init>`().
//                 ^ definition example/JavaAnnotation#MyClass#[T]
       void method(T t) {
//          ^^^^^^ definition example/JavaAnnotation#MyClass#method().
//                 ^ reference example/JavaAnnotation#MyClass#[T]
//                   ^ definition local0
         System.out.println(t);
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+9).
//                          ^ reference local0
       }
     }
   }
