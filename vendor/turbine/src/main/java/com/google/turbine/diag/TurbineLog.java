/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.diag;

import com.google.common.collect.ImmutableList;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.tools.Diagnostic;

/** A log that collects diagnostics. */
public class TurbineLog {

  private final Set<TurbineDiagnostic> diagnostics = new LinkedHashSet<>();

  public TurbineLogWithSource withSource(SourceFile source) {
    return new TurbineLogWithSource(source);
  }

  public ImmutableList<TurbineDiagnostic> diagnostics() {
    return ImmutableList.copyOf(diagnostics);
  }

  public void maybeThrow() {
    if (anyErrors()) {
      throw new TurbineError(diagnostics());
    }
  }

  public boolean anyErrors() {
    for (TurbineDiagnostic error : diagnostics) {
      if (error.severity().equals(Diagnostic.Kind.ERROR)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if a non-deferrable error was raised during annotation processing, i.e. an error
   * reported by an annotation processor.
   *
   * <p>Errors reported by turbine (e.g. missing symbols) are non-fatal, since they may be fixed by
   * code generated in later processing rounds.
   */
  public boolean errorRaised() {
    for (TurbineDiagnostic error : diagnostics) {
      if (error.kind().equals(ErrorKind.PROC) && error.severity().equals(Diagnostic.Kind.ERROR)) {
        return true;
      }
    }
    return false;
  }

  /** Reset the log between annotation processing rounds. */
  public void clear() {
    diagnostics.removeIf(TurbineDiagnostic::isError);
  }

  /** Reports an annotation processing diagnostic with no position information. */
  public void diagnostic(Diagnostic.Kind severity, String message) {
    diagnostics.add(TurbineDiagnostic.format(severity, ErrorKind.PROC, message));
  }

  /** A log for a specific source file. */
  public class TurbineLogWithSource {

    private final SourceFile source;

    private TurbineLogWithSource(SourceFile source) {
      this.source = source;
    }

    public void diagnostic(Diagnostic.Kind severity, int position, ErrorKind kind, Object... args) {
      diagnostics.add(TurbineDiagnostic.format(severity, source, position, kind, args));
    }

    public void error(int position, ErrorKind kind, Object... args) {
      diagnostic(Diagnostic.Kind.ERROR, position, kind, args);
    }
  }
}
