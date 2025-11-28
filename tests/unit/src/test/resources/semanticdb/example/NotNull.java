   package example;
//         ^^^^^^^ reference example/

   import java.lang.annotation.ElementType;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^^^^^^ reference java/lang/annotation/ElementType#
   import java.lang.annotation.Retention;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^^^^ reference java/lang/annotation/Retention#
   import java.lang.annotation.RetentionPolicy;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^^^^^^^^^^ reference java/lang/annotation/RetentionPolicy#
   import java.lang.annotation.Target;
//        ^^^^ reference java/
//             ^^^^ reference java/lang/
//                  ^^^^^^^^^^ reference java/lang/annotation/
//                             ^^^^^^ reference java/lang/annotation/Target#

   @Retention(RetentionPolicy.RUNTIME)
//  ^^^^^^^^^ reference java/lang/annotation/Retention#
//            ^^^^^^^^^^^^^^^ reference java/lang/annotation/RetentionPolicy#
//                            ^^^^^^^ reference java/lang/annotation/RetentionPolicy#RUNTIME.
   @Target(ElementType.TYPE_USE)
//  ^^^^^^ reference java/lang/annotation/Target#
//         ^^^^^^^^^^^ reference java/lang/annotation/ElementType#
//                     ^^^^^^^^ reference java/lang/annotation/ElementType#TYPE_USE.
   public @interface NotNull {}
//                   ^^^^^^^ definition example/NotNull#
