package scala.meta.infra;

import java.util.Optional;

public abstract class FeatureFlagProvider {
  public abstract Optional<Boolean> readBoolean(FeatureFlag flag);

  public final boolean readBooleanOrFalse(FeatureFlag flag) {
    return readBoolean(flag).orElse(false);
  }
}
