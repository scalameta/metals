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

   class JavaTypeVariable<T extends Comparable<T>> {
//       ^^^^^^^^^^^^^^^^ definition example/JavaTypeVariable#
//       ^^^^^^^^^^^^^^^^ definition example/JavaTypeVariable#`<init>`().
//                        ^ definition example/JavaTypeVariable#[T]
//                                  ^^^^^^^^^^ reference java/lang/Comparable#
//                                             ^ reference example/JavaTypeVariable#[T]
     <U> void method(U u, T t) {
//    ^ definition local0
//            ^^^^^^ definition example/JavaTypeVariable#method().
//                   ^ reference local0
//                     ^ definition local1
//                        ^ reference example/JavaTypeVariable#[T]
//                          ^ definition local2
       System.out.println(u);
//     ^^^^^^ reference java/lang/System#
//            ^^^ reference java/lang/System#out.
//                ^^^^^^^ reference java/io/PrintStream#println(+9).
//                        ^ reference local1
       System.out.println(t);
//     ^^^^^^ reference java/lang/System#
//            ^^^ reference java/lang/System#out.
//                ^^^^^^^ reference java/io/PrintStream#println(+9).
//                        ^ reference local2
     }

     class SubType extends JavaTypeVariable<String> {
//         ^^^^^^^ definition example/JavaTypeVariable#SubType#
//         ^^^^^^^ definition example/JavaTypeVariable#SubType#`<init>`().
//                         ^^^^^^^^^^^^^^^^ reference example/JavaTypeVariable#
//                                          ^^^^^^ reference java/lang/String#
       <U> void method(U u, String t) {
//      ^ definition local3
//              ^^^^^^ definition example/JavaTypeVariable#SubType#method().
//                     ^ reference local3
//                       ^ definition local4
//                          ^^^^^^ reference java/lang/String#
//                                 ^ definition local5
         System.out.println(u);
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+9).
//                          ^ reference local4
         System.out.println(t);
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+8).
//                          ^ reference local5
       }
     }

     @Target({ElementType.TYPE_PARAMETER})
//    ^^^^^^ reference java/lang/annotation/Target#
//            ^^^^^^^^^^^ reference java/lang/annotation/ElementType#
//                        ^^^^^^^^^^^^^^ reference java/lang/annotation/ElementType#TYPE_PARAMETER.
     @interface MyAnnotation {
//              ^^^^^^^^^^^^ definition example/JavaTypeVariable#MyAnnotation#
       String value() default "default";
//     ^^^^^^ reference java/lang/String#
//            ^^^^^ definition example/JavaTypeVariable#MyAnnotation#value().
     }

     class MyClass<@MyAnnotation("value") T extends Comparable<T>> {
//         ^^^^^^^ definition example/JavaTypeVariable#MyClass#
//         ^^^^^^^ definition example/JavaTypeVariable#MyClass#`<init>`().
//                  ^^^^^^^^^^^^ reference example/JavaTypeVariable#MyAnnotation#
//                                        ^ definition example/JavaTypeVariable#MyClass#[T]
//                                                  ^^^^^^^^^^ reference java/lang/Comparable#
//                                                             ^ reference example/JavaTypeVariable#MyClass#[T]
       <U> void method(U u, T t) {
//      ^ definition local6
//              ^^^^^^ definition example/JavaTypeVariable#MyClass#method().
//                     ^ reference local6
//                       ^ definition local7
//                          ^ reference example/JavaTypeVariable#MyClass#[T]
//                            ^ definition local8
         System.out.println(u);
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+9).
//                          ^ reference local7
         System.out.println(t);
//       ^^^^^^ reference java/lang/System#
//              ^^^ reference java/lang/System#out.
//                  ^^^^^^^ reference java/io/PrintStream#println(+9).
//                          ^ reference local8
       }
     }
   }
