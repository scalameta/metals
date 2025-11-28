   package example;
//         ^^^^^^^ reference example/

   import java.io.IOException;
//        ^^ reference io.
//        ^^^^ reference java.
//        ^^^^^^^^^^^ reference IOException.
   import java.nio.file.FileVisitResult;
//        ^^^ reference nio.
//        ^^^^ reference java.
//        ^^^^ reference file.
//        ^^^^^^^^^^^^^^^ reference FileVisitResult.
   import java.nio.file.Files;
//        ^^^ reference nio.
//        ^^^^ reference java.
//        ^^^^ reference file.
//        ^^^^^ reference Files.
   import java.nio.file.Path;
//        ^^^ reference nio.
//        ^^^^ reference java.
//        ^^^^ reference file.
//        ^^^^ reference Path.
   import java.nio.file.SimpleFileVisitor;
//        ^^^ reference nio.
//        ^^^^ reference java.
//        ^^^^ reference file.
//        ^^^^^^^^^^^^^^^^^ reference SimpleFileVisitor.
   import java.nio.file.attribute.BasicFileAttributes;
//        ^^^ reference nio.
//        ^^^^ reference java.
//        ^^^^ reference file.
//        ^^^^^^^^^ reference attribute.
//        ^^^^^^^^^^^^^^^^^^^ reference BasicFileAttributes.

   public final class JavaAnonymousClasses {
//                    ^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses# CLASS
//                    ^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses#`<init>`(). CONSTRUCTOR

     public static final SimpleFileVisitor<Path> FILE_VISITOR =
//                       ^^^^^^^^^^^^^^^^^ reference SimpleFileVisitor.
//                                         ^^^^ reference Path.
//                                               ^^^^^^^^^^^^ definition example/JavaAnonymousClasses#FILE_VISITOR. FIELD
         new SimpleFileVisitor<Path>() {
//           ^^^^^^^^^^^^^^^^^ reference SimpleFileVisitor.
//                             ^^^^ reference Path.
           @Override
//         ^^^^^^^^^ reference visitFile():
//          ^^^^^^^^ reference Override.
           public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
//                ^^^^^^^^^^^^^^^ reference FileVisitResult.
//                                          ^^^^ reference Path.
//                                                     ^^^^^^^^^^^^^^^^^^^ reference BasicFileAttributes.
//                                                                                       ^^^^^^^^^^^ reference IOException.
             if (file.getFileName().toString().endsWith(".java")) {
//               ^^^^^^^^ reference endsWith().
//               ^^^^^^^^ reference toString().
//               ^^^^^^^^^^^ reference getFileName().
               return FileVisitResult.CONTINUE;
//                    ^^^^^^^^ reference CONTINUE.
//                    ^^^^^^^^^^^^^^^ reference FileVisitResult.
             }
             return FileVisitResult.SKIP_SUBTREE;
//                  ^^^^^^^^^^^^ reference SKIP_SUBTREE.
//                  ^^^^^^^^^^^^^^^ reference FileVisitResult.
           }
         };

     private static @NotNull JavaClass createCompositeDescriptor(JavaEnum... sdkTypes)
//                   ^^^^^^^ reference NotNull.
//                           ^^^^^^^^^ reference JavaClass.
//                                     ^^^^^^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses#createCompositeDescriptor(). METHOD
//                                                               ^^^^^^^^ reference JavaEnum.
         throws IOException {
//              ^^^^^^^^^^^ reference IOException.
       Files.walkFileTree(
//     ^^^^^ reference Files.
//     ^^^^^^^^^^^^ reference walkFileTree().
           Path.of("."),
//         ^^ reference of().
//         ^^^^ reference Path.
           new SimpleFileVisitor<Path>() {
//             ^^^^^^^^^^^^^^^^^ reference SimpleFileVisitor.
//                               ^^^^ reference Path.
             @Override
//           ^^^^^^^^^ reference visitFile():
//            ^^^^^^^^ reference Override.
             public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
//                  ^^^^^^^^^^^^^^^ reference FileVisitResult.
//                                            ^^^^ reference Path.
//                                                       ^^^^^^^^^^^^^^^^^^^ reference BasicFileAttributes.
                 throws IOException {
//                      ^^^^^^^^^^^ reference IOException.
               if (file.getFileName().toString().endsWith(".java")) {
//                 ^^^^^^^^ reference endsWith().
//                 ^^^^^^^^ reference toString().
//                 ^^^^^^^^^^^ reference getFileName().
                 return FileVisitResult.CONTINUE;
//                      ^^^^^^^^ reference CONTINUE.
//                      ^^^^^^^^^^^^^^^ reference FileVisitResult.
               }
               return FileVisitResult.SKIP_SUBTREE;
//                    ^^^^^^^^^^^^ reference SKIP_SUBTREE.
//                    ^^^^^^^^^^^^^^^ reference FileVisitResult.
             }
           });
       return null;
     }
   }