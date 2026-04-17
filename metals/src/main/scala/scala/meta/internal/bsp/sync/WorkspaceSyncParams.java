package scala.meta.internal.bsp.sync;

public class WorkspaceSyncParams {
  private final String originId;
  private final String uri;
  private final String mode;

  public WorkspaceSyncParams(String originId, String uri, String mode) {
    this.originId = originId;
    this.uri = uri;
    this.mode = mode;
  }

  public String getOriginId() {
    return originId;
  }

  public String getUri() {
    return uri;
  }

  public String getMode() {
    return mode;
  }
}
