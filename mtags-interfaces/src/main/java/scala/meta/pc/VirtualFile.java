package scala.meta.pc;

/**
 * An interface of the virtual file that contains
 * path information and its content.
 */
public interface VirtualFile {

    /**
     * The path name of the virtual file.
     */
    String path();

    /**
     * The content of the virtual file.
     */
    String value();
}