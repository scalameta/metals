package scala.meta.pc.reports;

public interface ReportContext {
  Reporter unsanitized();
  Reporter incognito();
  Reporter bloop();
}
