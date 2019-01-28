package scala.meta.pc;

public interface OffsetParams {
    String filename();
    String text();
    int offset();
    void checkCanceled();
}
