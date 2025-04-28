package scala.meta.pc.reports;

import java.util.Optional;
import java.nio.file.Path;

public interface Reporter {
   Optional<Path> create(LazyReport report, Boolean ifVerbose);
   default Optional<Path> create(LazyReport report) {
      return create(report, Boolean.FALSE);
   }
}
