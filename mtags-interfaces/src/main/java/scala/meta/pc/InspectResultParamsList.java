package scala.meta.pc;

import java.util.List;

public interface InspectResultParamsList {
  List<String> params();
  Boolean isType();
  String implicitOrUsingKeyword();
}
