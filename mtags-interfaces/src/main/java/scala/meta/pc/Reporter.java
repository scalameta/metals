package scala.meta.pc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface Reporter {
    String name();
    Optional<Path> create(Report report, boolean ifVerbose);
    default Optional<Path> create(Report report) { return create(report, false); };
    List<TimestampedFile> cleanUpOldReports(int maxReportsNumber);
    List<TimestampedFile> getReports();
    void deleteAll();
    default String sanitize(String message) { return message; }
}
