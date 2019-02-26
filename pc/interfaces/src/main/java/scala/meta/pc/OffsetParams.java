package scala.meta.pc;

public interface OffsetParams {
    String filename();
    String text();
    int offset();
    CancelToken token();
    default void checkCanceled() {
        token().checkCanceled();
    }
}
