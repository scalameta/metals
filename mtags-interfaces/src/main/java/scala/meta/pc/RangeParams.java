package scala.meta.pc;

/**
 * Parameters for a presentation compiler request at a given offset in a single source file.
 */
public interface RangeParams extends OffsetParams {

    /**
     * The character offset of the request.
     */
    int endOffset();

}
