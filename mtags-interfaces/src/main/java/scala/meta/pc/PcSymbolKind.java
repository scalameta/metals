package scala.meta.pc;

public enum PcSymbolKind {
  UNKNOWN_KIND(0),
  METHOD(3),
  MACRO(6),
  TYPE(7),
  PARAMETER(8),
  TYPE_PARAMETER(9),
  OBJECT(10),
  PACKAGE(11),
  PACKAGE_OBJECT(12),
  CLASS(13),
  TRAIT(14),
  SELF_PARAMETER(17),
  INTERFACE(18),
  LOCAL(19),
  FIELD(20),
  CONSTRUCTOR(21);

  private int value;

  public int getValue() {
    return value;
  }

  private PcSymbolKind(int value) {
    this.value = value;
  }
}
