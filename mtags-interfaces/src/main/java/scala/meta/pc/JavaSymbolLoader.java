package scala.meta.pc;

public enum JavaSymbolLoader {
  /** Use the Turbine classpath as the 1st party symbol loader. */
  TURBINE_CLASSPATH,
  /** Use the Javac source path as the 1st party symbol loader. */
  JAVAC_SOURCEPATH,
}
