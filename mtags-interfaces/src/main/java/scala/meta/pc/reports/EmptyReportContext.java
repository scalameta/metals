package scala.meta.pc.reports;

public class EmptyReportContext implements ReportContext {
  private final Reporter emptyReporter = new EmptyReporter();
  @Override
  public Reporter unsanitized() {
    return emptyReporter;
  }

  @Override
  public Reporter incognito() {
    return emptyReporter;
  }

  @Override
  public Reporter bloop() {
    return emptyReporter;
  }
  
}
