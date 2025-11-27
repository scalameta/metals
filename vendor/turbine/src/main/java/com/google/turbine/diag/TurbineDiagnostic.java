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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.Objects;
import javax.tools.Diagnostic;
import org.jspecify.annotations.Nullable;

/** A compilation error. */
public class TurbineDiagnostic {

  private final Diagnostic.Kind severity;
  private final ErrorKind kind;
  private final ImmutableList<Object> args;
  private final @Nullable SourceFile source;
  private final int position;

  private TurbineDiagnostic(
      Diagnostic.Kind severity,
      ErrorKind kind,
      ImmutableList<Object> args,
      @Nullable SourceFile source,
      int position) {
    this.severity = requireNonNull(severity);
    this.kind = requireNonNull(kind);
    this.args = requireNonNull(args);
    this.source = source;
    this.position = position;
  }

  /** The diagnostic kind. */
  public ErrorKind kind() {
    return kind;
  }

  /**
   * The diagnostic severity (error, warning, ...). Turbine only produces errors, non-error
   * diagnostics are only ever created by annotation processors.
   */
  public Diagnostic.Kind severity() {
    return severity;
  }

  boolean isError() {
    return severity.equals(Diagnostic.Kind.ERROR);
  }

  /** The diagnostic message. */
  public String diagnostic() {
    StringBuilder sb = new StringBuilder(path());
    if (line() != -1) {
      sb.append(':').append(line());
    }
    sb.append(": error: ");
    sb.append(message()).append(System.lineSeparator());
    if (line() != -1 && column() != -1) {
      requireNonNull(source); // line and column imply source is non-null
      sb.append(CharMatcher.breakingWhitespace().trimTrailingFrom(source.lineMap().line(position)))
          .append(System.lineSeparator());
      sb.append(" ".repeat(column() - 1)).append('^');
    }
    return sb.toString();
  }

  /** The diagnostic arguments. */
  public ImmutableList<Object> args() {
    return args;
  }

  private static TurbineDiagnostic create(
      Diagnostic.Kind severity,
      ErrorKind kind,
      ImmutableList<Object> args,
      SourceFile source,
      int position) {
    switch (kind) {
      case SYMBOL_NOT_FOUND:
        {
          checkArgument(
              args.size() == 1 && getOnlyElement(args) instanceof ClassSymbol,
              "diagnostic (%s) has invalid argument %s",
              kind,
              args);
          break;
        }
      default: // fall out
    }
    return new TurbineDiagnostic(severity, kind, args, source, position);
  }

  public static TurbineDiagnostic format(Diagnostic.Kind severity, ErrorKind kind, String message) {
    return create(severity, kind, ImmutableList.of(message), null, -1);
  }

  /**
   * Formats a diagnostic.
   *
   * @param source the current source file
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineDiagnostic format(SourceFile source, ErrorKind kind, Object... args) {
    return create(Diagnostic.Kind.ERROR, kind, ImmutableList.copyOf(args), source, -1);
  }

  /**
   * Formats a diagnostic.
   *
   * @param position the diagnostic position
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineDiagnostic format(
      Diagnostic.Kind severity, SourceFile source, int position, ErrorKind kind, Object... args) {
    return create(severity, kind, ImmutableList.copyOf(args), source, position);
  }

  public TurbineDiagnostic withPosition(SourceFile source, int position) {
    return new TurbineDiagnostic(severity, kind, args, source, position);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, source, position);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof TurbineDiagnostic)) {
      return false;
    }
    TurbineDiagnostic that = (TurbineDiagnostic) obj;
    return severity.equals(that.severity)
        && kind.equals(that.kind)
        && args.equals(that.args)
        && Objects.equals(source, that.source)
        && position == that.position;
  }

  public String path() {
    return source != null && source.path() != null ? source.path() : "<>";
  }

  @SuppressWarnings("nullness") // position != -1 implies source is non-null
  public int line() {
    return position != -1 ? source.lineMap().lineNumber(position) : -1;
  }

  @SuppressWarnings("nullness") // position != -1 implies source is non-null
  public int column() {
    return position != -1 ? source.lineMap().column(position) + 1 : -1;
  }

  public String message() {
    return kind.format(args.toArray());
  }
}
