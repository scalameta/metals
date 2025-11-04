package scala.meta.internal.semanticdb.javac;

import java.util.Objects;

/**
 * Utilities to construct SemanticDB symbols.
 *
 * <p>Most parts of this file have been adapted from the companion Scala implementation:
 *
 * <p>https://github.com/scalameta/scalameta/blob/cf796cf2436b40494baf2bdc266623dc65264ad5/semanticdb/semanticdb/src/main/scala/scala/meta/internal/semanticdb/Scala.scala
 */
public final class SemanticdbSymbols {

  public static String NONE = "";
  public static String ROOT_PACKAGE = "_root_/";

  /** Creates a new global SemanticDB symbol. */
  public static String global(String owner, Descriptor desc) {
    if (desc == Descriptor.NONE) return SemanticdbSymbols.NONE;
    else if (!ROOT_PACKAGE.equals(owner)) return owner + desc.encode();
    else return desc.encode();
  }

  /** Creates a new local SemanticDB symbol. */
  public static String local(int suffix) {
    return "local" + suffix;
  }

  public static boolean isLocal(String symbol) {
    return symbol.startsWith("local");
  }

  public static boolean isGlobal(String symbol) {
    return !isLocal(symbol);
  }

  public static boolean isMethodOrField(String symbol) {
    return symbol.endsWith("#");
  }

  /**
   * A SemanticDB symbol is composed from a list of "descriptors".
   *
   * <p>For example, the symbol "com/example/Class#method()." is composed of
   *
   * <ul>
   *   <li>package descriptors "com/" and "example/"
   *   <li>type descriptor "Class/"
   *   <li>method descriptor "method()."
   * </ul>
   */
  public static final class Descriptor {

    public static Descriptor NONE = new Descriptor(Kind.None, "", "");

    public enum Kind {
      None,
      Local,
      Term,
      Method,
      Type,
      Package,
      Parameter,
      TypeParameter;
    }

    public final Kind kind;
    public final String name;
    public final String disambiguator;

    public Descriptor(Kind kind, String name) {
      this(kind, name, "");
    }

    public Descriptor(Kind kind, String name, String disambiguator) {
      this.kind = kind;
      this.name = name;
      this.disambiguator = disambiguator;
    }

    public Descriptor withName(String newName) {
      return new Descriptor(kind, newName, disambiguator);
    }

    public Descriptor withKind(Kind newKind) {
      return new Descriptor(newKind, name, disambiguator);
    }

    public static Descriptor local(String name) {
      return new Descriptor(Kind.Local, name);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Descriptor that = (Descriptor) o;
      return kind == that.kind
          && Objects.equals(name, that.name)
          && Objects.equals(disambiguator, that.disambiguator);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, name, disambiguator);
    }

    @Override
    public String toString() {
      return "Descriptor{"
          + "kind="
          + kind
          + ", name='"
          + name
          + '\''
          + ", disambiguator='"
          + disambiguator
          + '\''
          + '}';
    }

    public String encode() {
      switch (kind) {
        case None:
          return "";
        case Term:
          return encodeName(name) + ".";
        case Method:
          return encodeName(name) + disambiguator + ".";
        case Type:
          return encodeName(name) + "#";
        case Package:
          return encodeName(name) + "/";
        case Parameter:
          return "(" + encodeName(name) + ")";
        case TypeParameter:
          return "[" + encodeName(name) + "]";
        default:
          throw new IllegalArgumentException(String.format("%s", kind));
      }
    }

    /** Wraps non-alphanumeric identifiers in backticks, according to the SemanticDB spec. */
    private static String encodeName(String name) {
      if (name == null || name.isEmpty()) return "``";
      boolean isStartOk = Character.isJavaIdentifierStart(name.charAt(0));
      boolean isPartsOk = true;
      for (int i = 1; isPartsOk && i < name.length(); i++) {
        isPartsOk = Character.isJavaIdentifierPart(name.charAt(i));
      }
      if (isStartOk && isPartsOk) return name;
      else return "`" + name + "`";
    }
  }
}
