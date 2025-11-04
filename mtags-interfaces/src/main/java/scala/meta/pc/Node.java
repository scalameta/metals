package scala.meta.pc;

/**
 * Class used for including possible parent symbols in a scaladoc search, used for lazy evaluation.
 */
public interface Node {

  int start();

  int end();

  int tokenType();

  int tokenModifier();
}
