package scala.meta.pc.reports;

import java.net.URI;
import java.util.Optional;

public interface Report {
  String name();

  String shortSummary();

  Optional<URI> path();

  Optional<String> id();

  String fullText(boolean withIdAndSummary);
}
