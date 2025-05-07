package scala.meta.pc.reports;
import java.util.Optional;
import java.net.URI;

public interface Report {
    String name();
    String shortSummary();
    Optional<URI> path();
    Optional<String> id();
    String fullText(boolean withIdAndSummary);
}
