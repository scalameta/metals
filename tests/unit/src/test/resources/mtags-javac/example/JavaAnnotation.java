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

   @Deprecated(since = "1.0", forRemoval = true)
//  ^^^^^^^^^^ reference Deprecated.
//             ^^^^^ reference since().
//                            ^^^^^^^^^^ reference forRemoval().
   class JavaAnnotation {
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation# CLASS
//       ^^^^^^^^^^^^^^ definition example/JavaAnnotation#`<init>`(). CONSTRUCTOR

     @Target({ElementType.TYPE})
//    ^^^^^^ reference Target.
//            ^^^^ reference TYPE().
//            ^^^^^^^^^^^ reference ElementType().
     @interface MyAnnotation {
//              ^^^^^^^^^^^^ definition example/JavaAnnotation#MyAnnotation# CLASS
       String[] values() default {"default"};
//     ^^^^^^ reference String.
//              ^^^^^^ definition example/JavaAnnotation#MyAnnotation#values(). METHOD
     }

     public static final String CONSTANT = "constant";
//                       ^^^^^^ reference String.
//                              ^^^^^^^^ definition example/JavaAnnotation#CONSTANT. FIELD

     @MyAnnotation(values = {CONSTANT, "value2"})
//    ^^^^^^^^^^^^ reference MyAnnotation.
//                 ^^^^^^ reference values().
//                           ^^^^^^^^ reference CONSTANT().
     class MyClass<T> {
//         ^^^^^^^ definition example/JavaAnnotation#MyClass# CLASS
//         ^^^^^^^ definition example/JavaAnnotation#MyClass#`<init>`(). CONSTRUCTOR
//                 ^ definition example/JavaAnnotation#MyClass#[T] TYPE_PARAMETER
       void method(T t) {
//          ^^^^^^ definition example/JavaAnnotation#MyClass#method(). METHOD
         System.out.println(t);
//       ^^^ reference out.
//       ^^^^^^ reference System.
//       ^^^^^^^ reference println().
       }
     }
   }