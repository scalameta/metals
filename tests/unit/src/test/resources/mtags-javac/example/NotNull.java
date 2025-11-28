   package example;
//         ^^^^^^^ reference example/

   import java.lang.annotation.ElementType;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^^^^^ reference annotation.
//        ^^^^^^^^^^^ reference ElementType.
   import java.lang.annotation.Retention;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^^^^ reference Retention.
//        ^^^^^^^^^^ reference annotation.
   import java.lang.annotation.RetentionPolicy;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^^^^^ reference annotation.
//        ^^^^^^^^^^^^^^^ reference RetentionPolicy.
   import java.lang.annotation.Target;
//        ^^^^ reference java.
//        ^^^^ reference lang.
//        ^^^^^^ reference Target.
//        ^^^^^^^^^^ reference annotation.

   @Retention(RetentionPolicy.RUNTIME)
//  ^^^^^^^^^ reference Retention.
//            ^^^^^^^ reference RUNTIME().
//            ^^^^^^^^^^^^^^^ reference RetentionPolicy().
   @Target(ElementType.TYPE_USE)
//  ^^^^^^ reference Target.
//         ^^^^^^^^ reference TYPE_USE().
//         ^^^^^^^^^^^ reference ElementType().
   public @interface NotNull {}//                   ^^^^^^^ definition example/NotNull# CLASS
