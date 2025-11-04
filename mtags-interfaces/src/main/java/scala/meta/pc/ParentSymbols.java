package scala.meta.pc;

import java.util.List;

/**
 * Class used for including possible parent symbols in a scaladoc search, used for lazy evaluation.
 */
public abstract class ParentSymbols {

  /**
   * Obtain a list of parents of the current symbol search symbol.
   *
   * @return list of symbols
   */
  public abstract List<String> parents();
}
