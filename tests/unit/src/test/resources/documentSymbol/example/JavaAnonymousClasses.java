package /*example(Module):1*/example;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class /*example.JavaAnonymousClasses(Class):10*//*example.JavaAnonymousClasses#JavaAnonymousClasses(Constructor):10*/JavaAnonymousClasses {

  public static final SimpleFileVisitor<Path> /*example.JavaAnonymousClasses#FILE_VISITOR(Field):12*/FILE_VISITOR =
      new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.getFileName().toString().endsWith(".java")) {
            return FileVisitResult.CONTINUE;
          }
          return FileVisitResult.SKIP_SUBTREE;
        }
      };

  private static @NotNull JavaClass /*example.JavaAnonymousClasses#createCompositeDescriptor(Method):23*/createCompositeDescriptor(JavaEnum... sdkTypes)
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