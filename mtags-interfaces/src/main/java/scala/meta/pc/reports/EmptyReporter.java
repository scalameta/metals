package scala.meta.pc.reports;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class EmptyReporter implements Reporter {
  @Override
  public Optional<Path> create(Supplier<Report> report, Boolean ifVerbose) {
    return Optional.empty();
  }
}
