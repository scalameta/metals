package scala.meta.internal.bsp;

public class ProtocolExtension {
  private final String kind;
  private final Object data;

  public ProtocolExtension(String kind, Object data) {
    this.kind = kind;
    this.data = data;
  }

  public String getKind() {
    return kind;
  }

  public Object getData() {
    return data;
  }
}
