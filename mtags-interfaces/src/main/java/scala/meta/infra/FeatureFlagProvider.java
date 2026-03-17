package scala.meta.infra;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public abstract class FeatureFlagProvider {

  public abstract Optional<Boolean> readBoolean(FeatureFlag flag);

  public final boolean readBooleanOrFalse(FeatureFlag flag) {
    return readBoolean(flag).orElse(false);
  }

  /**
   * Read an integer value from the feature flag provider. Returns empty if the flag is not set or
   * not supported as an integer.
   */
  public abstract Optional<Integer> readInt(FeatureFlag flag, Integer defaultValue);

  /**
   * Read a list of strings from the feature flag provider. Returns an empty list if the flag is not
   * set or not supported as a string list.
   */
  public List<String> readStringList(FeatureFlag flag) {
    return Collections.emptyList();
  }
}
