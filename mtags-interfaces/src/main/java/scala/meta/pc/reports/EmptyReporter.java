package scala.meta.pc.reports;

import java.nio.file.Path;
import java.util.Optional;

public class EmptyReporter implements Reporter {
  @Override
  public Optional<Path> create(LazyReport report, Boolean ifVerbose) {
    return Optional.empty();
  }
}
