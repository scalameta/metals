   package example;
//         ^^^^^^^ reference example/

   import java.lang.annotation.ElementType;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^^^^^ reference annotation.
//        ^^^^^^^^^^^ reference ElementType.
   import java.lang.annotation.Target;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^ reference Target.
//        ^^^^^^^^^^ reference annotation.

   class JavaTypeVariable<T extends Comparable<T>> {
//       ^^^^^^^^^^^^^^^^ definition example/JavaTypeVariable# CLASS
//       ^^^^^^^^^^^^^^^^ definition example/JavaTypeVariable#`<init>`(). CONSTRUCTOR
//                        ^ definition example/JavaTypeVariable#[T] TYPE_PARAMETER
//                                  ^^^^^^^^^^ reference Comparable.
     <U> void method(U u, T t) {
//            ^^^^^^ definition example/JavaTypeVariable#method(). METHOD
       System.out.println(u);
//     ^^^ reference out.
//     ^^^^^^ reference System.
//     ^^^^^^^ reference println().
       System.out.println(t);
//     ^^^ reference out.
//     ^^^^^^ reference System.
//     ^^^^^^^ reference println().
     }

     class SubType extends JavaTypeVariable<String> {
//         ^^^^^^^ definition example/JavaTypeVariable#SubType# CLASS
//         ^^^^^^^ definition example/JavaTypeVariable#SubType#`<init>`(). CONSTRUCTOR
//                         ^^^^^^^^^^^^^^^^ reference JavaTypeVariable:
//                                          ^^^^^^ reference String:
       <U> void method(U u, String t) {
//              ^^^^^^ definition example/JavaTypeVariable#SubType#method(). METHOD
//                          ^^^^^^ reference String.
         System.out.println(u);
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
         System.out.println(t);
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }

     @Target({ElementType.TYPE_PARAMETER})
//    ^^^^^^ reference Target.
//            ^^^^^^^^^^^ reference ElementType().
//            ^^^^^^^^^^^^^^ reference TYPE_PARAMETER().
     @interface MyAnnotation {
//              ^^^^^^^^^^^^ definition example/JavaTypeVariable#MyAnnotation# CLASS
       String value() default "default";
//     ^^^^^^ reference String.
//            ^^^^^ definition example/JavaTypeVariable#MyAnnotation#value(). METHOD
     }

     class MyClass<@MyAnnotation("value") T extends Comparable<T>> {
//         ^^^^^^^ definition example/JavaTypeVariable#MyClass# CLASS
//         ^^^^^^^ definition example/JavaTypeVariable#MyClass#`<init>`(). CONSTRUCTOR
//                  ^^^^^^^^^^^^ reference MyAnnotation.
//                                        ^ definition example/JavaTypeVariable#MyClass#[T] TYPE_PARAMETER
//                                                  ^^^^^^^^^^ reference Comparable.
       <U> void method(U u, T t) {
//              ^^^^^^ definition example/JavaTypeVariable#MyClass#method(). METHOD
         System.out.println(u);
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
         System.out.println(t);
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }
   }