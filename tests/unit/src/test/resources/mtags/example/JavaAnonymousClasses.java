package example;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class JavaAnonymousClasses/*example.JavaAnonymousClasses#*//*example.JavaAnonymousClasses#`<init>`().*/ {

  public static final SimpleFileVisitor<Path> FILE_VISITOR/*example.JavaAnonymousClasses#FILE_VISITOR.*/ =
      new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.getFileName().toString().endsWith(".java")) {
            return FileVisitResult.CONTINUE;
          }
          return FileVisitResult.SKIP_SUBTREE;
        }
      };

  private static @NotNull JavaClass createCompositeDescriptor/*example.JavaAnonymousClasses#createCompositeDescriptor().*/(JavaEnum... sdkTypes)
      throws IOException {
    Files.walkFileTree(
        Path.of("."),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.getFileName().toString().endsWith(".java")) {
              return FileVisitResult.CONTINUE;
            }
            return FileVisitResult.SKIP_SUBTREE;
          }
        });
    return null;
  }
}
