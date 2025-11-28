package scala.meta.internal.jpc;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

/**
 * Provides a shared Names instance that can be reused across multiple javac Context instances.
 *
 * <p>The Names class is javac's interned string table. By sharing it across compilations, we avoid
 * re-creating and re-populating the name table on every compile request. This is safe because Names
 * is essentially a cache of immutable Name objects.
 *
 * <p>Note: Names depends on Options during construction, so we create it with a bootstrap context
 * and then inject it directly into subsequent contexts.
 */
public class SharedNames {

  private static volatile Names sharedInstance = null;
  private static final Object lock = new Object();

  /**
   * Pre-registers the shared Names instance into the given context. Must be called before any other
   * component requests Names from the context.
   *
   * @param context The javac Context to register the shared Names into
   */
  public static void preRegister(Context context) {
    Names names = getOrCreateSharedNames();
    // Directly put the shared instance into the context's hash table
    context.put(Names.namesKey, names);
  }

  /**
   * Gets or creates the shared Names instance. The first call creates the Names using a bootstrap
   * context. Subsequent calls return the same instance.
   */
  private static Names getOrCreateSharedNames() {
    if (sharedInstance == null) {
      synchronized (lock) {
        if (sharedInstance == null) {
          // Create a bootstrap context just for initializing Names
          Context bootstrapContext = new Context();
          sharedInstance = Names.instance(bootstrapContext);
        }
      }
    }
    return sharedInstance;
  }

  /** Clears the shared Names instance. This should only be called during testing or shutdown. */
  public static void reset() {
    synchronized (lock) {
      sharedInstance = null;
    }
  }
}
