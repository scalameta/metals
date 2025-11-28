   package example;
//         ^^^^^^^ reference example/

   import java.io.IOException;
//        ^^^^ reference java/
//             ^^ reference java/io/
//                ^^^^^^^^^^^ reference java/io/IOException#
   import java.nio.file.FileVisitResult;
//        ^^^^ reference java/
//             ^^^ reference java/nio/
//                 ^^^^ reference java/nio/file/
//                      ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
   import java.nio.file.Files;
//        ^^^^ reference java/
//             ^^^ reference java/nio/
//                 ^^^^ reference java/nio/file/
//                      ^^^^^ reference java/nio/file/Files#
   import java.nio.file.Path;
//        ^^^^ reference java/
//             ^^^ reference java/nio/
//                 ^^^^ reference java/nio/file/
//                      ^^^^ reference java/nio/file/Path#
   import java.nio.file.SimpleFileVisitor;
//        ^^^^ reference java/
//             ^^^ reference java/nio/
//                 ^^^^ reference java/nio/file/
//                      ^^^^^^^^^^^^^^^^^ reference java/nio/file/SimpleFileVisitor#
   import java.nio.file.attribute.BasicFileAttributes;
//        ^^^^ reference java/
//             ^^^ reference java/nio/
//                 ^^^^ reference java/nio/file/
//                      ^^^^^^^^^ reference java/nio/file/attribute/
//                                ^^^^^^^^^^^^^^^^^^^ reference java/nio/file/attribute/BasicFileAttributes#

   public final class JavaAnonymousClasses {
//                    ^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses#
//                    ^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses#`<init>`().

     public static final SimpleFileVisitor<Path> FILE_VISITOR =
//                       ^^^^^^^^^^^^^^^^^ reference java/nio/file/SimpleFileVisitor#
//                                         ^^^^ reference java/nio/file/Path#
//                                               ^^^^^^^^^^^^ definition example/JavaAnonymousClasses#FILE_VISITOR.
         new SimpleFileVisitor<Path>() {
//           ^^^^^^^^^^^^^^^^^ reference java/nio/file/SimpleFileVisitor#
//                             ^^^^ reference java/nio/file/Path#
           @Override
//          ^^^^^^^^ reference java/lang/Override#
           public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
//                ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                ^^^^^^^^^ definition local1
//                                          ^^^^ reference java/nio/file/Path#
//                                               ^^^^ definition local2
//                                                     ^^^^^^^^^^^^^^^^^^^ reference java/nio/file/attribute/BasicFileAttributes#
//                                                                         ^^^^^ definition local3
//                                                                                       ^^^^^^^^^^^ reference java/io/IOException#
             if (file.getFileName().toString().endsWith(".java")) {
//               ^^^^ reference local2
//                    ^^^^^^^^^^^ reference java/nio/file/Path#getFileName().
//                                  ^^^^^^^^ reference java/nio/file/Path#toString().
//                                             ^^^^^^^^ reference java/lang/String#endsWith().
               return FileVisitResult.CONTINUE;
//                    ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                    ^^^^^^^^ reference java/nio/file/FileVisitResult#CONTINUE.
             }
             return FileVisitResult.SKIP_SUBTREE;
//                  ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                  ^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#SKIP_SUBTREE.
           }
         };

     private static @NotNull JavaClass createCompositeDescriptor(JavaEnum... sdkTypes)
//                   ^^^^^^^ reference example/NotNull#
//                           ^^^^^^^^^ reference example/JavaClass#
//                                     ^^^^^^^^^^^^^^^^^^^^^^^^^ definition example/JavaAnonymousClasses#createCompositeDescriptor().
//                                                               ^^^^^^^^ reference example/JavaEnum#
//                                                                           ^^^^^^^^ definition local4
         throws IOException {
//              ^^^^^^^^^^^ reference java/io/IOException#
       Files.walkFileTree(
//     ^^^^^ reference java/nio/file/Files#
//           ^^^^^^^^^^^^ reference java/nio/file/Files#walkFileTree(+1).
           Path.of("."),
//         ^^^^ reference java/nio/file/Path#
//              ^^ reference java/nio/file/Path#of().
           new SimpleFileVisitor<Path>() {
//             ^^^^^^^^^^^^^^^^^ reference java/nio/file/SimpleFileVisitor#
//                               ^^^^ reference java/nio/file/Path#
             @Override
//            ^^^^^^^^ reference java/lang/Override#
             public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
//                  ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                  ^^^^^^^^^ definition local6
//                                            ^^^^ reference java/nio/file/Path#
//                                                 ^^^^ definition local7
//                                                       ^^^^^^^^^^^^^^^^^^^ reference java/nio/file/attribute/BasicFileAttributes#
//                                                                           ^^^^^ definition local8
                 throws IOException {
//                      ^^^^^^^^^^^ reference java/io/IOException#
               if (file.getFileName().toString().endsWith(".java")) {
//                 ^^^^ reference local7
//                      ^^^^^^^^^^^ reference java/nio/file/Path#getFileName().
//                                    ^^^^^^^^ reference java/nio/file/Path#toString().
//                                               ^^^^^^^^ reference java/lang/String#endsWith().
                 return FileVisitResult.CONTINUE;
//                      ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                      ^^^^^^^^ reference java/nio/file/FileVisitResult#CONTINUE.
               }
               return FileVisitResult.SKIP_SUBTREE;
//                    ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
//                                    ^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#SKIP_SUBTREE.
             }
           });
       return null;
     }
   }
