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

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import javax.tools.Diagnostic;

/** A compilation error. */
public class TurbineError extends Error {

  /** A diagnostic kind. */
  public enum ErrorKind {
    UNEXPECTED_INPUT("unexpected input: %s"),
    UNEXPECTED_IDENTIFIER("unexpected identifier '%s'"),
    UNEXPECTED_EOF("unexpected end of input"),
    UNTERMINATED_STRING("unterminated string literal"),
    UNTERMINATED_CHARACTER_LITERAL("unterminated char literal"),
    UNPAIRED_SURROGATE("unpaired surrogate 0x%x"),
    UNTERMINATED_EXPRESSION("unterminated expression, expected ';' not found"),
    INVALID_UNICODE("illegal unicode escape"),
    EMPTY_CHARACTER_LITERAL("empty char literal"),
    EXPECTED_TOKEN("expected token %s"),
    EXTENDS_AFTER_IMPLEMENTS("'extends' must come before 'implements'"),
    INVALID_LITERAL("invalid literal: %s"),
    UNEXPECTED_TYPE_PARAMETER("unexpected type parameter %s"),
    SYMBOL_NOT_FOUND("symbol not found %s"),
    CLASS_FILE_NOT_FOUND("could not locate class file for %s"),
    TYPE_PARAMETER_QUALIFIER("type parameter used as type qualifier"),
    UNEXPECTED_TOKEN("unexpected token: %s"),
    INVALID_ANNOTATION_ARGUMENT("invalid annotation argument"),
    MISSING_ANNOTATION_ARGUMENT("missing required annotation argument: %s"),
    CANNOT_RESOLVE("could not resolve %s"),
    EXPRESSION_ERROR("could not evaluate constant expression"),
    OPERAND_TYPE("bad operand type %s"),
    TYPE_CONVERSION("value %s of type %s cannot be converted to %s"),
    CYCLIC_HIERARCHY("cycle in class hierarchy: %s"),
    NOT_AN_ANNOTATION("%s is not an annotation"),
    ANNOTATION_VALUE_NAME("expected an annotation value of the form name=value"),
    NONREPEATABLE_ANNOTATION("%s is not @Repeatable"),
    DUPLICATE_DECLARATION("duplicate declaration of %s"),
    BAD_MODULE_INFO("unexpected declaration found in module-info"),
    UNCLOSED_COMMENT("unclosed comment"),
    UNEXPECTED_TYPE("unexpected type %s"),
    EXPECTED_INTERFACE("expected interface type"),
    UNEXPECTED_INTERFACE("unexpected interface type"),
    UNEXPECTED_MODIFIER("unexpected modifier: %s"),
    PROC("%s");

    private final String message;

    ErrorKind(String message) {
      this.message = message;
    }

    String format(Object... args) {
      return String.format(message, args);
    }
  }

  /**
   * Formats a diagnostic.
   *
   * @param source the current source file
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineError format(SourceFile source, ErrorKind kind, Object... args) {
    return new TurbineError(ImmutableList.of(TurbineDiagnostic.format(source, kind, args)));
  }

  /**
   * Formats a diagnostic.
   *
   * @param position the diagnostic position
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineError format(
      SourceFile source, int position, ErrorKind kind, Object... args) {
    return new TurbineError(
        ImmutableList.of(
            TurbineDiagnostic.format(Diagnostic.Kind.ERROR, source, position, kind, args)));
  }

  private final ImmutableList<TurbineDiagnostic> diagnostics;

  public TurbineError(ImmutableList<TurbineDiagnostic> diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String getMessage() {
    return diagnostics.stream().map(d -> d.diagnostic()).collect(joining(System.lineSeparator()));
  }

  public ImmutableList<TurbineDiagnostic> diagnostics() {
    return diagnostics;
  }
}
