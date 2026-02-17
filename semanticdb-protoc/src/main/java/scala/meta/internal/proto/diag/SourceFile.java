package scala.meta.internal.proto.diag;

/** Represents a source file being parsed. */
public final class SourceFile {

  private final String path;
  private final String content;
  private int[] lineOffsets;

  public SourceFile(String path, String content) {
    this.path = path;
    this.content = content;
  }

  public String path() {
    return path;
  }

  public String content() {
    return content;
  }

  public int length() {
    return content.length();
  }

  /** Get line offsets lazily computed. */
  public int[] lineOffsets() {
    if (lineOffsets == null) {
      lineOffsets = computeLineOffsets();
    }
    return lineOffsets;
  }

  private int[] computeLineOffsets() {
    java.util.List<Integer> offsets = new java.util.ArrayList<>();
    offsets.add(0);
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        offsets.add(i + 1);
      }
    }
    return offsets.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Convert an offset to a line number (0-based). */
  public int offsetToLine(int offset) {
    int[] lines = lineOffsets();
    int lo = 0;
    int hi = lines.length - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (lines[mid] <= offset) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return lo;
  }

  /** Convert an offset to a column number (0-based). */
  public int offsetToColumn(int offset) {
    int line = offsetToLine(offset);
    return offset - lineOffsets()[line];
  }
}
