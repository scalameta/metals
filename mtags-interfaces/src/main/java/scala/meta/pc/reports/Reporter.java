package scala.meta.pc.reports;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public interface Reporter {
  Optional<Path> create(Supplier<Report> report, Boolean ifVerbose);

  default Optional<Path> create(Supplier<Report> report) {
    return create(report, Boolean.FALSE);
  }
}
