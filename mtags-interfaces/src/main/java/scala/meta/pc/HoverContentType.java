package scala.meta.pc;

public enum HoverContentType {
  MARKDOWN("markdown"),
  PLAINTEXT("plaintext");

  private final String name;
  HoverContentType(String name) {
      this.name = name;
  }

  @Override
  public String toString() {
      return name;
  }
}
