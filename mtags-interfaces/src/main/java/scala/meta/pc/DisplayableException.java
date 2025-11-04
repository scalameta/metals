package scala.meta.pc;

/**
 * An expection, which message is to be displayed to the user. Currently used when code action
 * command cannot be executed but the appropriate condition could not be checked when creating the
 * action.
 */
public class DisplayableException extends RuntimeException {
  public DisplayableException(String message) {
    super(message);
  }
}
