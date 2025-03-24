package scala.meta.pc;

public enum ContentType {
  MARKDOWN("markdown"),
  PLAINTEXT("plaintext"),
  QUERY("query");

  private final String name;
  ContentType(String name) {
      this.name = name;
  }

  @Override
  public String toString() {
      return name;
  }
}
