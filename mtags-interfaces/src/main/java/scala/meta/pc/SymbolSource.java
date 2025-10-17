package scala.meta.pc;

import java.util.Optional;
import org.eclipse.lsp4j.Range;

/**
 * Represents metadata of java / scala symbol source such as classfile / tasty / source path and fullyQualifiedPath.
 */
public interface SymbolSource {

    /** Scala 3 symbol coming from tasty file from external dependency jar */
    public static class ExternalTastySymbolSource implements SymbolSource {
        private final String tastyJarPath;
        private final String fullyQualifiedPath;
        private final String inJarTastyFilePath;
        private final Range range;

        public ExternalTastySymbolSource(String tastyJarPath, String inJarTastyFilePath, String fullyQualifiedPath, Range range) {
            this.tastyJarPath = tastyJarPath;
            this.fullyQualifiedPath = fullyQualifiedPath;
            this.inJarTastyFilePath = inJarTastyFilePath;
            this.range = range;
        }

        public String getTastyJarPath() { return tastyJarPath; }
        public String getFullyQualifiedPath() { return fullyQualifiedPath; }
        public String getInJarTastyFilePath() { return inJarTastyFilePath; }
        public Range getRange() { return range; }

        @Override
        public String toString() {
            return String.format("ExternalTastySymbolSource[tastyJarPath: %s, inJarTastyFilePath: %s, fullyQualifiedPath: %s, range: %s]",
                tastyJarPath,
                inJarTastyFilePath,
                fullyQualifiedPath,
                range
            );
        }
    }

    /** Scala 3 symbol coming from tasty file from internal tasty file from other module */
    public static class InternalTastySymbolSource implements SymbolSource {
        private final String tastyPath; // probably not needed 
        private final String fullyQualifiedPath;
        private final String sourcePath;
        private final Range range;

        public InternalTastySymbolSource(String tastyPath, String sourcePath, String fullyQualifiedPath, Range range) {
            this.tastyPath = tastyPath;
            this.fullyQualifiedPath = fullyQualifiedPath;
            this.sourcePath = sourcePath;
            this.range = range;
        }

        public String getTastyPath() { return tastyPath; }
        public String getFullyQualifiedPath() { return fullyQualifiedPath; }
        public String getSourcePath() { return sourcePath; }
        public Range getRange() { return range; }

        @Override
        public String toString() {
            return String.format("InternalTastySymbolSource[tastyPath: %s, sourcePath: %s, fullyQualifiedPath: %s, range: %s]",
                tastyPath,
                sourcePath,
                fullyQualifiedPath,
                range
            );
        }
    }

    /** Scala 2 symbol coming from class file from external dependency jar */
    public static class ExternalClassFileSymbolSource implements SymbolSource {
        private final String classFileJarPath;
        private final String fullyQualifiedPath;
        private final String inJarClassFilePath;
        private final Boolean isJava;

        public ExternalClassFileSymbolSource(String classFileJarPath, String inJarClassFilePath, String fullyQualifiedPath, Boolean isJava) {
            this.classFileJarPath = classFileJarPath;
            this.fullyQualifiedPath = fullyQualifiedPath;
            this.isJava = isJava;
            this.inJarClassFilePath = inJarClassFilePath;
        }

        public String getClassFileJarPath() { return classFileJarPath; }
        public Boolean isJava() { return isJava; }
        public String getFullyQualifiedPath() { return fullyQualifiedPath; }
        public String getInJarClassFilePath() { return inJarClassFilePath; }

        @Override
        public String toString() {
            return String.format("ExternalClassFileSymbolSource[classFileJarPath: %s, inJarClassFilePath: %s, fullyQualifiedPath: %s, isJava %s]",
                classFileJarPath,
                inJarClassFilePath,
                fullyQualifiedPath,
                isJava 
            );
        }
    }

    /** Scala 2 symbol coming from class file from internal file from other modle */
    public static class InternalClassFileSymbolSource implements SymbolSource {
        private final String classFilePath;
        private final String fullyQualifiedPath;
        private final Boolean isJava;

        public InternalClassFileSymbolSource(String classFilePath, String fullyQualifiedPath, Boolean isJava) {
            this.classFilePath = classFilePath;
            this.isJava = isJava;
            this.fullyQualifiedPath = fullyQualifiedPath;
        }

        public String getFullyQualifiedPath() { return fullyQualifiedPath; }
        public Boolean isJava() { return isJava; }
        public String getClassFilePath() { return classFilePath; }

        @Override
        public String toString() {
            return String.format("InternalClassFileSymbolSource[classFilePath: %s, fullyQualifiedPath: %s, isJava: %s]",
                classFilePath,
                fullyQualifiedPath,
                isJava
            );
        }
    }

    /** Scala symbol coming from same compilation unit */
    public static class ScalaFileSymbolSource implements SymbolSource {
        private final String sourcePath;
        private final String fullyQualifiedPath;
        private final Range range;

        public ScalaFileSymbolSource(String sourcePath, String fullyQualifiedPath, Range range) {
            this.sourcePath = sourcePath;
            this.fullyQualifiedPath = fullyQualifiedPath;
            this.range= range;
        }

        public String getFullyQualifiedPath() { return fullyQualifiedPath; }
        public Range getRange() { return range; }
        public String getSourcePath() { return sourcePath; }

        @Override
        public String toString() {
            return String.format("ScalaFileSymbolSource[sourcePath: %s, fullyQualifiedPath: %s, range %s]",
                sourcePath,
                fullyQualifiedPath,
                range
            );
        }
    }
}
