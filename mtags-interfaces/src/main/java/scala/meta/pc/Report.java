package scala.meta.pc;

import java.util.Optional;
import java.lang.StringBuilder;

public interface Report {
    String name();
    String text();
    String shortSummary();
    Optional<String> path();
    Optional<String> id();
    Optional<Throwable> error();

    public Report extend(String moreInfo);
    public String fullText(boolean withIdAndSummary);
}
