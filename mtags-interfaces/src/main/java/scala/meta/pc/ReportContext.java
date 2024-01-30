package scala.meta.pc;

import java.util.List;
import java.util.Arrays;
import scala.meta.pc.Reporter;

public interface ReportContext {
    Reporter unsanitized();
    Reporter incognito();
    Reporter bloop();

    default List<Reporter> all() { 
      return Arrays.asList(unsanitized(), incognito(), bloop());
    };
    default List<Reporter> allToZip() {
      return Arrays.asList(incognito(), bloop());
    };
    default void cleanUpOldReports(int maxReportsNumber) {
      all().stream().forEach(report -> report.cleanUpOldReports(maxReportsNumber));
    };
    default void deleteAll() {
      all().stream().forEach(report -> report.deleteAll());
    };
}
