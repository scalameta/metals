/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.scalaparse;

import static com.google.turbine.scalaparse.ScalaToken.ABSTRACT;
import static com.google.turbine.scalaparse.ScalaToken.ARROW;
import static com.google.turbine.scalaparse.ScalaToken.AT;
import static com.google.turbine.scalaparse.ScalaToken.BACKQUOTED_IDENT;
import static com.google.turbine.scalaparse.ScalaToken.CASE;
import static com.google.turbine.scalaparse.ScalaToken.CLASS;
import static com.google.turbine.scalaparse.ScalaToken.COLON;
import static com.google.turbine.scalaparse.ScalaToken.COMMA;
import static com.google.turbine.scalaparse.ScalaToken.DEF;
import static com.google.turbine.scalaparse.ScalaToken.DOT;
import static com.google.turbine.scalaparse.ScalaToken.EOF;
import static com.google.turbine.scalaparse.ScalaToken.EQUALS;
import static com.google.turbine.scalaparse.ScalaToken.EXTENDS;
import static com.google.turbine.scalaparse.ScalaToken.FALSE;
import static com.google.turbine.scalaparse.ScalaToken.FINAL;
import static com.google.turbine.scalaparse.ScalaToken.IDENTIFIER;
import static com.google.turbine.scalaparse.ScalaToken.IF;
import static com.google.turbine.scalaparse.ScalaToken.IMPLICIT;
import static com.google.turbine.scalaparse.ScalaToken.IMPORT;
import static com.google.turbine.scalaparse.ScalaToken.LBRACE;
import static com.google.turbine.scalaparse.ScalaToken.LBRACK;
import static com.google.turbine.scalaparse.ScalaToken.LAZY;
import static com.google.turbine.scalaparse.ScalaToken.LPAREN;
import static com.google.turbine.scalaparse.ScalaToken.NEW;
import static com.google.turbine.scalaparse.ScalaToken.NEWLINE;
import static com.google.turbine.scalaparse.ScalaToken.NEWLINES;
import static com.google.turbine.scalaparse.ScalaToken.NULL;
import static com.google.turbine.scalaparse.ScalaToken.OBJECT;
import static com.google.turbine.scalaparse.ScalaToken.OVERRIDE;
import static com.google.turbine.scalaparse.ScalaToken.PACKAGE;
import static com.google.turbine.scalaparse.ScalaToken.PRIVATE;
import static com.google.turbine.scalaparse.ScalaToken.PROTECTED;
import static com.google.turbine.scalaparse.ScalaToken.RBRACE;
import static com.google.turbine.scalaparse.ScalaToken.RBRACK;
import static com.google.turbine.scalaparse.ScalaToken.RPAREN;
import static com.google.turbine.scalaparse.ScalaToken.SEALED;
import static com.google.turbine.scalaparse.ScalaToken.SEMI;
import static com.google.turbine.scalaparse.ScalaToken.SUBTYPE;
import static com.google.turbine.scalaparse.ScalaToken.SUPERTYPE;
import static com.google.turbine.scalaparse.ScalaToken.TRAIT;
import static com.google.turbine.scalaparse.ScalaToken.THIS;
import static com.google.turbine.scalaparse.ScalaToken.TRUE;
import static com.google.turbine.scalaparse.ScalaToken.TYPE;
import static com.google.turbine.scalaparse.ScalaToken.USCORE;
import static com.google.turbine.scalaparse.ScalaToken.VAL;
import static com.google.turbine.scalaparse.ScalaToken.VAR;
import static com.google.turbine.scalaparse.ScalaToken.VIEWBOUND;
import static com.google.turbine.scalaparse.ScalaToken.WITH;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.parse.UnicodeEscapePreprocessor;
import com.google.turbine.scalaparse.ScalaTree.ClassDef;
import com.google.turbine.scalaparse.ScalaTree.DefDef;
import com.google.turbine.scalaparse.ScalaTree.Param;
import com.google.turbine.scalaparse.ScalaTree.ParamList;
import com.google.turbine.scalaparse.ScalaTree.TypeDef;
import com.google.turbine.scalaparse.ScalaTree.TypeParam;
import com.google.turbine.scalaparse.ScalaTree.ValDef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A minimal Scala parser that builds an outline tree and skips bodies. */
public final class ScalaParser {

  private final ScalaLexer lexer;
  private ScalaToken token;
  private String value;
  private int position;

  private boolean hasPeek;
  private ScalaToken peekToken;
  private String peekValue;
  private int peekPosition;

  private final Deque<String> packagePrefix = new ArrayDeque<>();
  private final Deque<Map<String, ExprInfo>> valueScopes = new ArrayDeque<>();
  private final Deque<Map<Integer, String>> deferredReturnRefs = new ArrayDeque<>();

  public static ScalaTree.CompUnit parse(String source) {
    return parse(new SourceFile(null, source));
  }

  public static ScalaTree.CompUnit parse(SourceFile source) {
    ScalaLexer lexer = new ScalaStreamLexer(new UnicodeEscapePreprocessor(source));
    return new ScalaParser(lexer).compilationUnit(source);
  }

  private ScalaParser(ScalaLexer lexer) {
    this.lexer = lexer;
    this.valueScopes.push(new HashMap<>());
    next();
  }

  private ScalaTree.CompUnit compilationUnit(SourceFile source) {
    Builder<ScalaTree.Stat> stats = ImmutableList.builder();
    while (token != EOF) {
      if (isSeparator(token)) {
        next();
        continue;
      }
      if (token == PACKAGE) {
        if (peek() == OBJECT) {
          stats.add(parsePackageObject());
        } else {
          parsePackageClause(stats);
        }
        continue;
      }
      if (token == IMPORT) {
        stats.add(parseImport());
        continue;
      }
      ImmutableList<ScalaTree.Defn> defns = parseTopDef();
      stats.addAll(defns);
    }
    return new ScalaTree.CompUnit(stats.build(), source);
  }

  private void parsePackageClause(Builder<ScalaTree.Stat> stats) {
    int start = position;
    accept(PACKAGE);
    ImmutableList<String> name = parseQualId();
    if (token == LBRACE) {
      accept(LBRACE);
      packagePrefix.addAll(name);
      while (token != RBRACE && token != EOF) {
        if (isSeparator(token)) {
          next();
          continue;
        }
        if (token == PACKAGE && peek() == OBJECT) {
          stats.add(parsePackageObject());
          continue;
        }
        if (token == IMPORT) {
          stats.add(parseImport());
          continue;
        }
        ImmutableList<ScalaTree.Defn> defns = parseTopDef();
        stats.addAll(defns);
      }
      accept(RBRACE);
      for (int i = 0; i < name.size(); i++) {
        packagePrefix.removeLast();
      }
      return;
    }
    // Package clause without braces extends the current prefix for the rest of the file.
    for (String segment : name) {
      packagePrefix.addLast(segment);
    }
    if (token == SEMI || token == NEWLINE || token == NEWLINES) {
      next();
    }
  }

  private ScalaTree.ImportStat parseImport() {
    int start = position;
    accept(IMPORT);
    String text = parseImportText();
    return new ScalaTree.ImportStat(currentPackageName(), text, start);
  }

  private String parseImportText() {
    StringBuilder sb = new StringBuilder();
    EnumSet<ScalaToken> stops = EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF);
    int braceDepth = 0;
    appendTokenText(sb, token, value);
    if (token == LBRACE) {
      braceDepth++;
    } else if (token == RBRACE) {
      braceDepth = Math.max(0, braceDepth - 1);
    }
    next();
    while (token != EOF && !stops.contains(token)) {
      sb.append(' ');
      appendTokenText(sb, token, value);
      if (token == LBRACE) {
        braceDepth++;
      } else if (token == RBRACE) {
        braceDepth = Math.max(0, braceDepth - 1);
      }
      next();
    }
    while (token == RBRACE && braceDepth > 0) {
      sb.append(' ');
      appendTokenText(sb, token, value);
      braceDepth--;
      next();
    }
    return sb.toString().trim();
  }

  private ImmutableList<ScalaTree.Defn> parseTopDef() {
    int startPosition = position;
    ScalaToken startToken = token;
    ImmutableList<String> modifiers = parseModifiers();
    boolean isCase = false;
    if (token == CASE) {
      isCase = true;
      next();
    }
    if (token == CLASS) {
      return ImmutableList.of(parseClass(modifiers, isCase, ClassDef.Kind.CLASS, false));
    }
    if (token == TRAIT) {
      return ImmutableList.of(parseClass(modifiers, isCase, ClassDef.Kind.TRAIT, false));
    }
    if (token == OBJECT) {
      return ImmutableList.of(parseClass(modifiers, isCase, ClassDef.Kind.OBJECT, false));
    }
    if (token == DEF) {
      return ImmutableList.of(parseDef(modifiers));
    }
    if (token == VAL) {
      return parseVals(modifiers, false);
    }
    if (token == VAR) {
      return parseVals(modifiers, true);
    }
    if (token == TYPE) {
      return ImmutableList.of(parseTypeDef(modifiers));
    }

    // Unrecognized, skip expression.
    skipExpr(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
    if (token == startToken && position == startPosition && token != EOF) {
      // Ensure we always make forward progress to avoid infinite loops.
      next();
    }
    return ImmutableList.of();
  }

  private ScalaTree.Defn parsePackageObject() {
    ImmutableList<String> modifiers = parseModifiers();
    int start = position;
    accept(PACKAGE);
    accept(OBJECT);
    String name = parseName();
    ImmutableList<TypeParam> tparams = parseTypeParams();
    ImmutableList<String> parents = parseParents();
    TemplateBody body = parseTemplateBody();
    return new ClassDef(
        currentPackageName(),
        name,
        ClassDef.Kind.OBJECT,
        false,
        true,
        modifiers,
        tparams,
        ImmutableList.of(),
        parents,
        body.imports(),
        body.members(),
        start);
  }

  private ClassDef parseClass(
      ImmutableList<String> modifiers,
      boolean isCase,
      ClassDef.Kind kind,
      boolean isPackageObject) {
    int start = position;
    next();
    String name = parseName();
    ImmutableList<TypeParam> tparams = parseTypeParams();
    skipCtorModifiers();
    ImmutableList<ParamList> ctorParams = parseParamLists();
    ImmutableList<String> parents = parseParents();
    TemplateBody body = parseTemplateBody();
    return new ClassDef(
        currentPackageName(),
        name,
        kind,
        isCase,
        isPackageObject,
        modifiers,
        tparams,
        ctorParams,
        parents,
        body.imports(),
        body.members(),
        start);
  }

  private DefDef parseDef(ImmutableList<String> modifiers) {
    int start = position;
    accept(DEF);
    String name;
    if (token == THIS) {
      name = "this";
      next();
    } else {
      name = parseName();
    }
    ImmutableList<TypeParam> tparams = parseTypeParams();
    ImmutableList<ParamList> params = parseParamLists();
    Map<String, String> paramTypes = new HashMap<>();
    for (ParamList list : params) {
      for (Param param : list.params()) {
        if (param.type() != null) {
          paramTypes.put(param.name(), param.type());
        }
      }
    }
    String returnType = null;
    boolean hasBody = false;
    boolean synchronizedBody = false;
    if (token == COLON) {
      next();
      returnType = parseTypeText(EnumSet.of(EQUALS, SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
    }
    if (token == EQUALS) {
      hasBody = true;
      next();
      if (isSynchronizedExprStart()) {
        synchronizedBody = true;
      }
      String deferredRef = null;
      if (returnType == null) {
        deferredRef = inferDeferredReturnRef();
        returnType = inferLiteralType(token);
        if (returnType == null && token.isIdentifier()) {
          returnType = paramTypes.get(value);
        }
        if (returnType == null) {
          returnType = inferTypeFromExprStart(token, value);
        }
        if (returnType == null) {
          ExprInfo inferred = parseExprInfo(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
          if (inferred != null) {
            returnType = inferred.rawType();
          }
        } else if (token == LBRACE) {
          skipBlock();
        } else {
          skipExpr(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
        }
      } else if (token == LBRACE) {
        skipBlock();
      } else {
        skipExpr(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
      }
      if (returnType == null && deferredRef != null) {
        Map<Integer, String> deferred = deferredReturnRefs.peek();
        if (deferred != null) {
          deferred.put(start, deferredRef);
        }
      }
    } else if (token == LBRACE) {
      hasBody = true;
      if (returnType == null) {
        String inferred = skipBlockAndInferType();
        if (returnType == null) {
          returnType = inferred;
        }
      } else {
        skipBlock();
      }
    }
    ImmutableList<String> adjusted = modifiers;
    if (!hasBody && !modifiers.contains("abstract")) {
      adjusted =
          ImmutableList.<String>builder().addAll(modifiers).add("abstract").build();
    }
    if (synchronizedBody && !adjusted.contains("synchronized")) {
      adjusted = ImmutableList.<String>builder().addAll(adjusted).add("synchronized").build();
    }
    if (returnType != null) {
      Map<String, ExprInfo> scope = currentValueScope();
      if (scope != null) {
        scope.put(name, ExprInfo.ofType(returnType));
      }
    }
    return new DefDef(
        currentPackageName(), name, adjusted, tparams, params, returnType, start);
  }

  private ImmutableList<ScalaTree.Defn> parseVals(
      ImmutableList<String> modifiers, boolean isVar) {
    return parseVals(modifiers, isVar, /* depth= */ null);
  }

  private ImmutableList<ScalaTree.Defn> parseVals(
      ImmutableList<String> modifiers, boolean isVar, @Nullable BlockDepth depth) {
    int start = position;
    next();
    EnumSet<ScalaToken> patternTypeStops = patternTypeStops(/* allowArrow= */ true);
    List<Pattern> patterns = new ArrayList<>();
    patterns.add(normalizeValPattern(parsePattern(patternTypeStops)));
    while (token == COMMA) {
      next();
      patterns.add(normalizeValPattern(parsePattern(patternTypeStops)));
    }
    String explicitType = null;
    boolean hasExplicitType = false;
    if (token == COLON) {
      next();
      hasExplicitType = true;
      explicitType = parseTypeText(EnumSet.of(EQUALS, COMMA, SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
    }
    boolean hasDefault = false;
    ExprInfo rhsInfo = null;
    if (token == EQUALS) {
      hasDefault = true;
      next();
      if (depth == null) {
        rhsInfo = parseExprInfo(EnumSet.of(COMMA, SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
      } else {
        rhsInfo = parseExprInfoInBlock(EnumSet.of(COMMA, SEMI, NEWLINE, NEWLINES), depth);
      }
    }

    List<ScalaTree.Defn> out = new ArrayList<>();
    Map<String, ExprInfo> scope = currentValueScope();
    for (Pattern pattern : patterns) {
      if (pattern == null) {
        continue;
      }
      List<String> binders = new ArrayList<>();
      pattern.collectBinders(binders);
      String patternExplicitType = null;
      boolean patternHasExplicitType = false;
      if (pattern instanceof TypedPattern typed) {
        patternExplicitType = typed.type();
        patternHasExplicitType = patternExplicitType != null && !patternExplicitType.isEmpty();
      }
      Map<String, String> binderTypes = new HashMap<>();
      if (rhsInfo != null) {
        pattern.assignTypes(rhsInfo, binderTypes);
      }
      for (String binder : binders) {
        boolean binderExplicit = false;
        String type;
        if (patternHasExplicitType && binders.size() == 1) {
          type = patternExplicitType;
          binderExplicit = patternExplicitType != null;
        } else if (hasExplicitType && binders.size() == 1) {
          type = explicitType;
          binderExplicit = explicitType != null;
        } else {
          type = binderTypes.get(binder);
        }
        if (type == null) {
          // Keep un-inferred member/value types usable in outlines.
          type = "java/lang/Object";
        }
        ValDef val =
            new ValDef(
                currentPackageName(),
                binder,
                isVar,
                modifiers,
                type,
                binderExplicit,
                hasDefault,
                start);
        out.add(val);
        if (scope != null) {
          ExprInfo infoForScope = null;
          if (pattern instanceof BinderPattern && rhsInfo != null) {
            if (type != null && rhsInfo.rawType() != null && !type.equals(rhsInfo.rawType())) {
              infoForScope =
                  new ExprInfo(type, rhsInfo.typeArgs(), rhsInfo.tupleElementTypes());
            } else {
              infoForScope = rhsInfo;
            }
          } else if (type != null) {
            infoForScope = ExprInfo.ofType(type);
          } else if (hasExplicitType && explicitType != null && binders.size() == 1) {
            infoForScope = ExprInfo.ofType(explicitType);
          }
          if (infoForScope != null) {
            scope.put(binder, infoForScope);
          }
        }
      }
    }

    return ImmutableList.copyOf(out);
  }

  private @Nullable ExprInfo parseExprInfo(EnumSet<ScalaToken> stops) {
    ExprInfo info = parseSimpleExprInfo();
    if (!stops.contains(token)) {
      skipExpr(stops);
    }
    return info;
  }

  private @Nullable ExprInfo parseExprInfoInBlock(EnumSet<ScalaToken> stops, BlockDepth depth) {
    ExprInfo info = parseSimpleExprInfo();
    if (depth.value > 0 && !stops.contains(token)) {
      skipExprInBlock(stops, depth);
    }
    return info;
  }

  private @Nullable ExprInfo parseSimpleExprInfo() {
    if (token.isLiteral() || token == TRUE || token == FALSE || token == NULL) {
      String type = inferLiteralType(token);
      next();
      return type == null ? null : ExprInfo.ofType(type);
    }
    if (token == LPAREN) {
      return parseTupleOrParenExprInfo();
    }
    if (token == NEW) {
      next();
      if (token.isIdentifier()) {
        String typeText =
            parseTypeText(EnumSet.of(LPAREN, LBRACE, NEWLINE, NEWLINES, SEMI, COMMA, RBRACE, EOF));
        if (token == LPAREN) {
          skipDelimited(LPAREN, RPAREN);
        }
        if (token == LBRACE) {
          skipDelimited(LBRACE, RBRACE);
        }
        if (typeText != null && !typeText.isEmpty()) {
          return ExprInfo.ofType(typeText);
        }
      }
      return null;
    }
    if (token == THIS) {
      next();
      // `this` expressions in defs (for example `def getInstance = this`) should infer
      // a singleton receiver type rather than falling back to Unit.
      if (token == DOT) {
        next();
        if (token.isIdentifier()) {
          String member = value;
          next();
          if ("type".equals(member)) {
            return ExprInfo.ofType("this/type");
          }
          if (token == LPAREN) {
            parseCallArgTypes();
          }
        }
      }
      return ExprInfo.ofType("this/type");
    }
    if (token == IDENTIFIER || token == BACKQUOTED_IDENT) {
      boolean backquoted = token == BACKQUOTED_IDENT;
      String name = value;
      next();
      boolean qualified = false;
      String current = name;
      String base = name;
      String lastSegment = name;
      while (token == DOT) {
        qualified = true;
        next();
        if (!token.isIdentifier()) {
          break;
        }
        base = current;
        lastSegment = value;
        current = current + "/" + value;
        next();
      }
      ExprInfo info;
      if (token == LPAREN) {
        if (qualified && "apply".equals(lastSegment) && isTypeLike(base)) {
          info = parseApplyExprInfo(base);
        } else {
          info = parseCallExprInfo(current);
        }
      } else if (!qualified) {
        ExprInfo scoped = lookupValue(name);
        if (scoped != null) {
          info = scoped;
        } else if (!backquoted && isTypeLike(current)) {
          String inferred = singletonObjectType(current);
          info = ExprInfo.ofType(inferred);
        } else {
          info = null;
        }
      } else if (!backquoted && isTypeLike(current)) {
        String inferred = current;
        info = ExprInfo.ofType(inferred);
      } else {
        info = null;
      }
      while (true) {
        if (token == NEWLINE || token == NEWLINES) {
          if (peek() != DOT) {
            break;
          }
          next();
        }
        if (token != DOT) {
          break;
        }
        next();
        if (!token.isIdentifier()) {
          break;
        }
        String member = value;
        next();
        boolean invoked = false;
        if (token == LPAREN) {
          parseCallArgTypes();
          invoked = true;
        }
        info = refineChainedExprInfo(info, member, invoked);
      }
      return info;
    }
    return null;
  }

  private @Nullable ExprInfo refineChainedExprInfo(
      @Nullable ExprInfo receiver, String member, boolean invoked) {
    if (receiver == null || receiver.rawType() == null) {
      return null;
    }
    String rawType = receiver.rawType();
    if (invoked && member != null && (member.startsWith("create") || "fallbackConf".equals(member))) {
      String replacement =
          "createOptional".equals(member) ? "OptionalConfigEntry" : "ConfigEntry";
      String createdType = rewriteBuilderResultType(rawType, replacement);
      if (!createdType.equals(rawType)) {
        return ExprInfo.ofType(createdType);
      }
    }
    if (invoked) {
      return ExprInfo.ofType(rawType);
    }
    if (member != null && isTypeLike(member)) {
      return ExprInfo.ofType(rawType + "/" + member);
    }
    return receiver;
  }

  private String rewriteBuilderResultType(String rawType, String replacementSuffix) {
    if (rawType == null || rawType.isEmpty()) {
      return rawType;
    }
    int slash = rawType.lastIndexOf('/');
    String prefix = slash >= 0 ? rawType.substring(0, slash + 1) : "";
    String simple = slash >= 0 ? rawType.substring(slash + 1) : rawType;
    if ("ConfigBuilder".equals(simple)) {
      return prefix + replacementSuffix;
    }
    if (simple.endsWith("ConfigBuilder")) {
      return prefix
          + simple.substring(0, simple.length() - "ConfigBuilder".length())
          + replacementSuffix;
    }
    if (simple.endsWith("Builder")) {
      return prefix + simple.substring(0, simple.length() - "Builder".length()) + replacementSuffix;
    }
    return rawType;
  }

  private Pattern normalizeValPattern(Pattern pattern) {
    if (pattern instanceof ConstructorPattern ctor) {
      if (ctor.args().isEmpty() && ctor.name().indexOf('/') < 0) {
        return new BinderPattern(ctor.name());
      }
      ImmutableList.Builder<Pattern> args = ImmutableList.builder();
      for (Pattern arg : ctor.args()) {
        args.add(normalizeValPattern(arg));
      }
      return new ConstructorPattern(ctor.name(), args.build());
    }
    if (pattern instanceof TuplePattern tuple) {
      ImmutableList.Builder<Pattern> normalized = ImmutableList.builder();
      for (Pattern element : tuple.elements()) {
        normalized.add(normalizeValPattern(element));
      }
      return new TuplePattern(normalized.build());
    }
    if (pattern instanceof AliasPattern alias) {
      return new AliasPattern(alias.name(), normalizeValPattern(alias.rhs()));
    }
    if (pattern instanceof TypedPattern typed) {
      return new TypedPattern(normalizeValPattern(typed.pattern()), typed.type());
    }
    return pattern;
  }

  private String singletonObjectType(String name) {
    if (name == null || name.isEmpty() || name.endsWith("$")) {
      return name;
    }
    return name + "$";
  }

  private ExprInfo parseCallExprInfo(String name) {
    if (!isTypeLike(name)) {
      parseCallArgTypes();
      if (name.indexOf('/') < 0) {
        ExprInfo scoped = lookupValue(name);
        if (scoped != null) {
          return scoped;
        }
      }
      return null;
    }
    List<String> typeArgs = parseCallArgTypes();
    return new ExprInfo(name, typeArgs, List.of());
  }

  private ExprInfo parseApplyExprInfo(String resultType) {
    List<String> typeArgs = parseCallArgTypes();
    return new ExprInfo(resultType, typeArgs, List.of());
  }

  private List<String> parseCallArgTypes() {
    List<String> argTypes = new ArrayList<>();
    boolean allKnown = true;
    accept(LPAREN);
    if (token != RPAREN) {
      while (true) {
        ExprInfo arg = parseSimpleExprInfo();
        String argType = arg == null ? null : arg.rawType();
        if (argType == null) {
          allKnown = false;
        } else {
          argTypes.add(argType);
        }
        if (token != COMMA && token != RPAREN) {
          skipExpr(EnumSet.of(COMMA, RPAREN));
        }
        if (token == COMMA) {
          next();
          continue;
        }
        break;
      }
    }
    accept(RPAREN);
    return allKnown ? List.copyOf(argTypes) : List.of();
  }

  private @Nullable ExprInfo parseTupleOrParenExprInfo() {
    accept(LPAREN);
    if (token == RPAREN) {
      accept(RPAREN);
      return ExprInfo.ofType("Unit");
    }
    ExprInfo first = parseSimpleExprInfo();
    String firstType = first == null ? null : first.rawType();
    List<String> elementTypes = new ArrayList<>();
    boolean allKnown = true;
    int elementCount = 0;
    elementCount++;
    if (firstType == null) {
      allKnown = false;
    } else {
      elementTypes.add(firstType);
    }
    if (token != COMMA && token != RPAREN) {
      skipExpr(EnumSet.of(COMMA, RPAREN));
    }
    boolean sawComma = false;
    while (token == COMMA) {
      sawComma = true;
      next();
      ExprInfo elem = parseSimpleExprInfo();
      String elemType = elem == null ? null : elem.rawType();
      elementCount++;
      if (elemType == null) {
        allKnown = false;
      } else {
        elementTypes.add(elemType);
      }
      if (token != COMMA && token != RPAREN) {
        skipExpr(EnumSet.of(COMMA, RPAREN));
      }
    }
    accept(RPAREN);
    if (!sawComma) {
      return first;
    }
    String tupleType = "scala/Tuple" + elementCount;
    List<String> tupleElements = allKnown ? List.copyOf(elementTypes) : List.of();
    return new ExprInfo(tupleType, List.of(), tupleElements);
  }

  private Pattern parsePattern() {
    return parsePattern(patternTypeStops(/* allowArrow= */ false));
  }

  private Pattern parsePattern(EnumSet<ScalaToken> typeStops) {
    Pattern pattern = parsePatternSimple(typeStops);
    if (token == AT) {
      next();
      Pattern rhs = parsePattern(typeStops);
      if (pattern instanceof BinderPattern binder) {
        pattern = new AliasPattern(binder.name(), rhs);
      } else {
        pattern = rhs;
      }
    }
    if (token == COLON) {
      next();
      String explicitType = parseTypeText(typeStops);
      if (explicitType != null && !explicitType.isEmpty()) {
        pattern = new TypedPattern(pattern, explicitType);
      }
    }
    return pattern;
  }

  private Pattern parsePatternSimple(EnumSet<ScalaToken> typeStops) {
    if (token == USCORE) {
      next();
      return new WildcardPattern();
    }
    if (token == LPAREN) {
      return parseTuplePattern(typeStops);
    }
    if (token == IDENTIFIER || token == BACKQUOTED_IDENT) {
      boolean backquoted = token == BACKQUOTED_IDENT;
      String name = value;
      next();
      boolean qualified = false;
      while (token == DOT) {
        qualified = true;
        next();
        if (!token.isIdentifier()) {
          break;
        }
        name = name + "/" + value;
        next();
      }
      if (token == LPAREN) {
        return new ConstructorPattern(name, parsePatternArgs(typeStops));
      }
      if (!qualified && (backquoted || isBinderName(name))) {
        return new BinderPattern(name);
      }
      return new ConstructorPattern(name, ImmutableList.of());
    }
    return new WildcardPattern();
  }

  private Pattern parseTuplePattern(EnumSet<ScalaToken> typeStops) {
    accept(LPAREN);
    if (token == RPAREN) {
      accept(RPAREN);
      return new WildcardPattern();
    }
    Pattern first = parsePattern(typeStops);
    if (token != COMMA) {
      if (token != RPAREN) {
        // Be tolerant of complex patterns we don't understand yet.
        skipToMatchingParen();
        return new WildcardPattern();
      }
      accept(RPAREN);
      return first;
    }
    List<Pattern> elements = new ArrayList<>();
    elements.add(first);
    while (token == COMMA) {
      next();
      elements.add(parsePattern(typeStops));
    }
    if (token != RPAREN) {
      // Be tolerant of complex patterns we don't understand yet.
      skipToMatchingParen();
      return new TuplePattern(ImmutableList.copyOf(elements));
    }
    accept(RPAREN);
    return new TuplePattern(ImmutableList.copyOf(elements));
  }

  private void skipToMatchingParen() {
    int depth = 0;
    while (token != EOF) {
      if (token == LPAREN) {
        depth++;
      } else if (token == RPAREN) {
        if (depth == 0) {
          next();
          return;
        }
        depth = Math.max(0, depth - 1);
      }
      next();
    }
  }

  private ImmutableList<Pattern> parsePatternArgs(EnumSet<ScalaToken> typeStops) {
    accept(LPAREN);
    ImmutableList.Builder<Pattern> args = ImmutableList.builder();
    if (token != RPAREN) {
      while (true) {
        args.add(parsePattern(typeStops));
        if (token == COMMA) {
          next();
          continue;
        }
        break;
      }
    }
    accept(RPAREN);
    return args.build();
  }

  private EnumSet<ScalaToken> patternTypeStops(boolean allowArrow) {
    EnumSet<ScalaToken> stops =
        EnumSet.of(COMMA, RPAREN, AT, IF, EQUALS, NEWLINE, NEWLINES, SEMI, RBRACE);
    if (!allowArrow) {
      stops.add(ARROW);
    }
    return stops;
  }

  private boolean isTypeLike(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    int slash = name.lastIndexOf('/');
    String segment = slash >= 0 ? name.substring(slash + 1) : name;
    return !segment.isEmpty() && Character.isUpperCase(segment.charAt(0));
  }

  private boolean isBinderName(String name) {
    return name != null && !name.isEmpty() && Character.isLowerCase(name.charAt(0));
  }

  private Map<String, ExprInfo> currentValueScope() {
    Map<String, ExprInfo> scope = valueScopes.peek();
    if (scope == null) {
      scope = new HashMap<>();
      valueScopes.push(scope);
    }
    return scope;
  }

  private @Nullable ExprInfo lookupValue(String name) {
    for (Map<String, ExprInfo> scope : valueScopes) {
      ExprInfo info = scope.get(name);
      if (info != null) {
        return info;
      }
    }
    return null;
  }

  private sealed interface Pattern
      permits BinderPattern,
          TuplePattern,
          ConstructorPattern,
          WildcardPattern,
          AliasPattern,
          TypedPattern {
    void collectBinders(List<String> out);

    void assignTypes(ExprInfo rhs, Map<String, String> out);
  }

  private record BinderPattern(String name) implements Pattern {
    @Override
    public void collectBinders(List<String> out) {
      out.add(name);
    }

    @Override
    public void assignTypes(ExprInfo rhs, Map<String, String> out) {
      if (rhs != null && rhs.rawType() != null) {
        out.putIfAbsent(name, rhs.rawType());
      }
    }
  }

  private record TuplePattern(ImmutableList<Pattern> elements) implements Pattern {
    @Override
    public void collectBinders(List<String> out) {
      for (Pattern element : elements) {
        element.collectBinders(out);
      }
    }

    @Override
    public void assignTypes(ExprInfo rhs, Map<String, String> out) {
      if (rhs != null && rhs.tupleElementTypes() != null) {
        List<String> types = rhs.tupleElementTypes();
        if (types.size() == elements.size()) {
          for (int i = 0; i < elements.size(); i++) {
            String type = types.get(i);
            if (type != null) {
              elements.get(i).assignTypes(ExprInfo.ofType(type), out);
            }
          }
          return;
        }
      }
      for (Pattern element : elements) {
        element.assignTypes(null, out);
      }
    }
  }

  private record ConstructorPattern(String name, ImmutableList<Pattern> args) implements Pattern {
    @Override
    public void collectBinders(List<String> out) {
      for (Pattern arg : args) {
        arg.collectBinders(out);
      }
    }

    @Override
    public void assignTypes(ExprInfo rhs, Map<String, String> out) {
      if (rhs != null && rhs.typeArgs() != null) {
        List<String> types = rhs.typeArgs();
        if (types.size() >= args.size()) {
          for (int i = 0; i < args.size(); i++) {
            String type = types.get(i);
            if (type != null) {
              args.get(i).assignTypes(ExprInfo.ofType(type), out);
            }
          }
          return;
        }
      }
      for (Pattern arg : args) {
        arg.assignTypes(null, out);
      }
    }
  }

  private record WildcardPattern() implements Pattern {
    @Override
    public void collectBinders(List<String> out) {}

    @Override
    public void assignTypes(ExprInfo rhs, Map<String, String> out) {}
  }

  private record AliasPattern(String name, Pattern rhs) implements Pattern {
    @Override
    public void collectBinders(List<String> out) {
      out.add(name);
      rhs.collectBinders(out);
    }

    @Override
    public void assignTypes(ExprInfo rhsInfo, Map<String, String> out) {
      if (rhsInfo != null && rhsInfo.rawType() != null) {
        out.putIfAbsent(name, rhsInfo.rawType());
      }
      rhs.assignTypes(rhsInfo, out);
    }
  }

  private record TypedPattern(Pattern pattern, String type) implements Pattern {
    @Override
    public void collectBinders(List<String> out) {
      pattern.collectBinders(out);
    }

    @Override
    public void assignTypes(ExprInfo rhsInfo, Map<String, String> out) {
      if (type != null && !type.isEmpty()) {
        pattern.assignTypes(ExprInfo.ofType(type), out);
      } else {
        pattern.assignTypes(rhsInfo, out);
      }
    }
  }

  private record ExprInfo(
      @Nullable String rawType,
      List<String> typeArgs,
      List<String> tupleElementTypes) {
    static ExprInfo ofType(String rawType) {
      return new ExprInfo(rawType, List.of(), List.of());
    }
  }

  private static final class BlockDepth {
    int value;

    BlockDepth(int value) {
      this.value = value;
    }
  }

  private TypeDef parseTypeDef(ImmutableList<String> modifiers) {
    int start = position;
    accept(TYPE);
    String name = parseName();
    ImmutableList<TypeParam> tparams = parseTypeParams();
    String lower = null;
    String upper = null;
    ImmutableList.Builder<String> viewBounds = ImmutableList.builder();
    ImmutableList.Builder<String> contextBounds = ImmutableList.builder();
    if (token == SUPERTYPE) {
      next();
      lower = parseTypeText(boundStopTokens());
    }
    if (token == SUBTYPE) {
      next();
      upper = parseTypeText(boundStopTokens());
    }
    while (token == VIEWBOUND || token == COLON) {
      if (token == VIEWBOUND) {
        next();
        viewBounds.add(parseTypeText(boundStopTokens()));
      } else {
        next();
        contextBounds.add(parseTypeText(boundStopTokens()));
      }
    }
    String rhs = null;
    if (token == EQUALS) {
      next();
      rhs = parseTypeText(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
    }
    return new TypeDef(
        currentPackageName(),
        name,
        modifiers,
        tparams,
        lower,
        upper,
        viewBounds.build(),
        contextBounds.build(),
        rhs,
        start);
  }

  private ImmutableList<ParamList> parseParamLists() {
    ImmutableList.Builder<ParamList> lists = ImmutableList.builder();
    List<String> priorParams = new ArrayList<>();
    while (true) {
      while (token == NEWLINE || token == NEWLINES) {
        next();
      }
      if (token != LPAREN) {
        break;
      }
      lists.add(parseParamList(priorParams));
    }
    return lists.build();
  }

  private ParamList parseParamList(List<String> priorParams) {
    accept(LPAREN);
    ImmutableList.Builder<Param> params = ImmutableList.builder();
    if (token != RPAREN) {
      while (true) {
        Param param = parseParam(priorParams);
        params.add(param);
        priorParams.add(param.name());
        if (token == COMMA) {
          next();
          continue;
        }
        break;
      }
    }
    accept(RPAREN);
    return new ParamList(params.build());
  }

  private Param parseParam(List<String> priorParams) {
    ImmutableList<String> modifiers = parseParamModifiers();
    String name = parseName();
    String type = null;
    if (token == COLON) {
      next();
      type = parseTypeText(EnumSet.of(EQUALS, COMMA, RPAREN, NEWLINE, NEWLINES, SEMI));
    }
    boolean hasDefault = false;
    boolean defaultUsesParam = false;
    if (token == EQUALS) {
      hasDefault = true;
      next();
      defaultUsesParam = skipExprAndDetectParamRef(EnumSet.of(COMMA, RPAREN), priorParams);
    }
    return new Param(name, modifiers, type, hasDefault, defaultUsesParam);
  }

  private String inferLiteralType(ScalaToken token) {
    return switch (token) {
      case INT_LITERAL -> "Int";
      case LONG_LITERAL -> "Long";
      case FLOAT_LITERAL -> "Float";
      case DOUBLE_LITERAL -> "Double";
      case CHAR_LITERAL -> "Char";
      case STRING_LITERAL -> "String";
      case SYMBOL_LITERAL -> "scala/Symbol";
      case TRUE, FALSE -> "Boolean";
      case NULL -> "AnyRef";
      default -> null;
    };
  }

  private String inferTypeFromExprStart(ScalaToken token, @Nullable String value) {
    if (token == IDENTIFIER || token == BACKQUOTED_IDENT) {
      if (value != null && !value.isEmpty() && Character.isUpperCase(value.charAt(0))) {
        ScalaToken nextToken = peek();
        if (nextToken == LPAREN || nextToken == DOT) {
          return null;
        }
        return singletonObjectType(value);
      }
    }
    return null;
  }

  private ImmutableList<TypeParam> parseTypeParams() {
    if (token != LBRACK) {
      return ImmutableList.of();
    }
    accept(LBRACK);
    ImmutableList.Builder<TypeParam> tparams = ImmutableList.builder();
    if (token != RBRACK) {
      while (true) {
        tparams.add(parseTypeParam());
        if (token == COMMA) {
          next();
          continue;
        }
        break;
      }
    }
    accept(RBRACK);
    return tparams.build();
  }

  private TypeParam parseTypeParam() {
    while (token == AT) {
      skipAnnotation();
    }
    String variance = null;
    if (token == IDENTIFIER && ("+".equals(value) || "-".equals(value))) {
      variance = value;
      next();
    }
    while (token == AT) {
      skipAnnotation();
    }
    String name = parseName();
    if (token == LBRACK) {
      skipDelimited(LBRACK, RBRACK);
    }
    String lower = null;
    String upper = null;
    ImmutableList.Builder<String> viewBounds = ImmutableList.builder();
    ImmutableList.Builder<String> contextBounds = ImmutableList.builder();
    while (true) {
      if (token == SUPERTYPE) {
        next();
        lower = parseTypeText(boundStopTokens());
        continue;
      }
      if (token == SUBTYPE) {
        next();
        upper = parseTypeText(boundStopTokens());
        continue;
      }
      if (token == VIEWBOUND) {
        next();
        viewBounds.add(parseTypeText(boundStopTokens()));
        continue;
      }
      if (token == COLON) {
        next();
        contextBounds.add(parseTypeText(boundStopTokens()));
        continue;
      }
      break;
    }
    return new TypeParam(name, variance, lower, upper, viewBounds.build(), contextBounds.build());
  }

  private ImmutableList<String> parseParents() {
    while (token == NEWLINE || token == NEWLINES) {
      next();
    }
    if (token != EXTENDS) {
      return ImmutableList.of();
    }
    accept(EXTENDS);
    ImmutableList.Builder<String> parents = ImmutableList.builder();
    if (token == LBRACE) {
      skipBlock();
    }
    parents.add(parseTypeText(parentStopTokens()));
    while (token == WITH) {
      next();
      parents.add(parseTypeText(parentStopTokens()));
    }
    return parents.build();
  }

  private TemplateBody parseTemplateBody() {
    if (token != LBRACE) {
      return new TemplateBody(ImmutableList.of(), ImmutableList.of());
    }
    valueScopes.push(new HashMap<>());
    Map<Integer, String> deferredRefs = new HashMap<>();
    deferredReturnRefs.push(deferredRefs);
    accept(LBRACE);
    ImmutableList.Builder<String> imports = ImmutableList.builder();
    ImmutableList.Builder<ScalaTree.Defn> members = ImmutableList.builder();
    while (token != RBRACE && token != EOF) {
      if (isSeparator(token)) {
        next();
        continue;
      }
      if (token == THIS || (token == IDENTIFIER && (peek() == COLON || peek() == ARROW))) {
        skipSelfType();
        continue;
      }
      if (token == IMPORT) {
        ScalaTree.ImportStat imp = parseImport();
        imports.add(imp.text());
        continue;
      }
      ImmutableList<String> modifiers = parseModifiers();
      boolean isCase = false;
      if (token == CASE) {
        isCase = true;
        next();
      }
      if (token == CLASS) {
        members.add(parseClass(modifiers, isCase, ClassDef.Kind.CLASS, false));
        continue;
      }
      if (token == TRAIT) {
        members.add(parseClass(modifiers, isCase, ClassDef.Kind.TRAIT, false));
        continue;
      }
      if (token == OBJECT) {
        members.add(parseClass(modifiers, isCase, ClassDef.Kind.OBJECT, false));
        continue;
      }
      if (token == DEF) {
        members.add(parseDef(modifiers));
        continue;
      }
      if (token == VAL) {
        members.addAll(parseVals(modifiers, false));
        continue;
      }
      if (token == VAR) {
        members.addAll(parseVals(modifiers, true));
        continue;
      }
      if (token == TYPE) {
        members.add(parseTypeDef(modifiers));
        continue;
      }

      skipExpr(EnumSet.of(SEMI, NEWLINE, NEWLINES, RBRACE, EOF));
    }
    accept(RBRACE);
    deferredReturnRefs.pop();
    valueScopes.pop();
    ImmutableList<ScalaTree.Defn> resolvedMembers =
        resolveDeferredMethodReturns(members.build(), deferredRefs);
    return new TemplateBody(imports.build(), resolvedMembers);
  }

  private void skipSelfType() {
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && brace == 0 && token == ARROW) {
        next();
        return;
      }
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> brace++;
        case RBRACE -> brace = Math.max(0, brace - 1);
        default -> {}
      }
      next();
    }
  }

  private @Nullable String inferDeferredReturnRef() {
    if (token != IDENTIFIER && token != BACKQUOTED_IDENT) {
      return null;
    }
    String name = value;
    ScalaToken nextToken = peek();
    if (nextToken == LPAREN
        || nextToken == SEMI
        || nextToken == NEWLINE
        || nextToken == NEWLINES
        || nextToken == RBRACE
        || nextToken == EOF) {
      return name;
    }
    return null;
  }

  private ImmutableList<ScalaTree.Defn> resolveDeferredMethodReturns(
      ImmutableList<ScalaTree.Defn> members, Map<Integer, String> deferredRefs) {
    if (members.isEmpty()) {
      return members;
    }
    Map<String, String> knownTypes = new HashMap<>();
    for (ScalaTree.Defn member : members) {
      if (member instanceof DefDef def && def.returnType() != null) {
        knownTypes.putIfAbsent(def.name(), def.returnType());
      } else if (member instanceof ValDef val && val.type() != null) {
        knownTypes.putIfAbsent(val.name(), val.type());
      }
    }
    ImmutableList<ScalaTree.Defn> current = members;
    int maxPasses = Math.max(1, members.size());
    for (int pass = 0; pass < maxPasses; pass++) {
      boolean changed = false;
      ImmutableList.Builder<ScalaTree.Defn> nextMembers = ImmutableList.builder();
      for (ScalaTree.Defn member : current) {
        if (member instanceof DefDef def && def.returnType() == null) {
          String refName = deferredRefs.get(def.position());
          if (refName != null) {
            String resolved = knownTypes.get(refName);
            if (resolved == null && "copy".equals(refName)) {
              resolved = "this/type";
            }
            if (resolved != null) {
              DefDef rewritten =
                  new DefDef(
                      def.packageName(),
                      def.name(),
                      def.modifiers(),
                      def.typeParams(),
                      def.paramLists(),
                      resolved,
                      def.position());
              nextMembers.add(rewritten);
              knownTypes.putIfAbsent(rewritten.name(), resolved);
              changed = true;
              continue;
            }
          }
        }
        if (member instanceof DefDef def && def.returnType() != null) {
          knownTypes.putIfAbsent(def.name(), def.returnType());
        }
        nextMembers.add(member);
      }
      current = nextMembers.build();
      if (!changed) {
        break;
      }
    }
    ImmutableList.Builder<ScalaTree.Defn> finalized = ImmutableList.builder();
    for (ScalaTree.Defn member : current) {
      if (member instanceof DefDef def && def.returnType() == null && !"this".equals(def.name())) {
        // IDE-facing outlines prefer object fallback when return inference is unavailable.
        finalized.add(
            new DefDef(
                def.packageName(),
                def.name(),
                def.modifiers(),
                def.typeParams(),
                def.paramLists(),
                "java/lang/Object",
                def.position()));
      } else {
        finalized.add(member);
      }
    }
    return finalized.build();
  }

  private record TemplateBody(ImmutableList<String> imports, ImmutableList<ScalaTree.Defn> members) {}

  private ImmutableList<String> parseModifiers() {
    ImmutableList.Builder<String> mods = ImmutableList.builder();
    boolean sawAnnotationOrModifier = false;
    while (true) {
      if (isSeparator(token)) {
        if (sawAnnotationOrModifier) {
          next();
          continue;
        }
        break;
      }
      if (token == AT) {
        String throwsType = parseAnnotationAndExtractThrows();
        if (throwsType != null && !throwsType.isEmpty()) {
          mods.add("throws:" + throwsType);
        }
        sawAnnotationOrModifier = true;
        continue;
      }
      String text = token == IDENTIFIER ? value : token.toString();
      if (isModifierToken(token, value)) {
        boolean visibilityModifier = "private".equals(text) || "protected".equals(text);
        next();
        boolean scopedVisibility = false;
        boolean thisScope = false;
        if (visibilityModifier && token == LBRACK) {
          scopedVisibility = true;
          thisScope = consumeAccessQualifierIsThis();
        }
        if (visibilityModifier && scopedVisibility && !thisScope) {
          continue;
        }
        mods.add(text);
        sawAnnotationOrModifier = true;
        continue;
      }
      break;
    }
    return mods.build();
  }

  private void skipCtorModifiers() {
    while (true) {
      if (token == AT) {
        skipAnnotation();
        continue;
      }
      if (token == PRIVATE || token == PROTECTED) {
        next();
        if (token == LBRACK) {
          consumeAccessQualifierIsThis();
        }
        continue;
      }
      break;
    }
  }

  private ImmutableList<String> parseParamModifiers() {
    ImmutableList.Builder<String> mods = ImmutableList.builder();
    while (true) {
      if (token == AT) {
        skipAnnotation();
        continue;
      }
      String text = token == IDENTIFIER ? value : token.toString();
      if (isParamModifierToken(token, value)) {
        boolean visibilityModifier = "private".equals(text) || "protected".equals(text);
        next();
        boolean scopedVisibility = false;
        boolean thisScope = false;
        if (visibilityModifier && token == LBRACK) {
          scopedVisibility = true;
          thisScope = consumeAccessQualifierIsThis();
        }
        if (visibilityModifier && scopedVisibility && !thisScope) {
          continue;
        }
        mods.add(text);
        continue;
      }
      break;
    }
    return mods.build();
  }

  private boolean consumeAccessQualifierIsThis() {
    if (token != LBRACK) {
      return false;
    }
    accept(LBRACK);
    boolean thisScope = token == THIS || (token == IDENTIFIER && "this".equals(value));
    while (token != RBRACK && token != EOF) {
      next();
    }
    if (token == RBRACK) {
      accept(RBRACK);
    }
    return thisScope;
  }

  private @Nullable String parseAnnotationAndExtractThrows() {
    accept(AT);
    String annotationName = null;
    if (token.isIdentifier()) {
      annotationName = value;
      next();
      while (token == DOT) {
        next();
        if (token.isIdentifier()) {
          annotationName = value;
          next();
        } else {
          break;
        }
      }
      if (token == LBRACK) {
        accept(LBRACK);
        String typeText = parseTypeText(EnumSet.of(RBRACK));
        accept(RBRACK);
        if ("throws".equals(annotationName)) {
          return typeText == null || typeText.isEmpty() ? null : typeText;
        }
      }
    }
    if (token == LPAREN) {
      if ("throws".equals(annotationName)) {
        return parseThrowsTypeArg();
      }
      skipDelimited(LPAREN, RPAREN);
    }
    return null;
  }

  private @Nullable String parseThrowsTypeArg() {
    accept(LPAREN);
    String typeText = null;
    if ((token == IDENTIFIER || token == BACKQUOTED_IDENT) && "classOf".equals(value)) {
      next();
      if (token == LBRACK) {
        accept(LBRACK);
        typeText = parseTypeText(EnumSet.of(RBRACK));
        accept(RBRACK);
      }
    } else if (token != RPAREN) {
      typeText = parseTypeText(EnumSet.of(RPAREN));
    }
    if (token != RPAREN) {
      skipExpr(EnumSet.of(RPAREN));
    }
    accept(RPAREN);
    if (typeText == null || typeText.isEmpty()) {
      return null;
    }
    return typeText;
  }

  private void skipAnnotation() {
    accept(AT);
    if (token.isIdentifier()) {
      next();
      while (token == DOT) {
        next();
        if (token.isIdentifier()) {
          next();
        } else {
          break;
        }
      }
      if (token == LBRACK) {
        skipDelimited(LBRACK, RBRACK);
      }
    }
    if (token == LPAREN) {
      skipDelimited(LPAREN, RPAREN);
    }
  }

  private boolean isSynchronizedExprStart() {
    return (token == IDENTIFIER || token == BACKQUOTED_IDENT) && "synchronized".equals(value);
  }

  private void skipDelimited(ScalaToken open, ScalaToken close) {
    accept(open);
    int depth = 1;
    while (depth > 0 && token != EOF) {
      if (token == open) {
        depth++;
      } else if (token == close) {
        depth--;
      } else if (token == LBRACE) {
        // balance braces within delimiters
        skipBlock();
        continue;
      }
      next();
    }
  }

  private void skipBlock() {
    accept(LBRACE);
    int depth = 1;
    while (depth > 0 && token != EOF) {
      if (token == LBRACE) {
        depth++;
      } else if (token == RBRACE) {
        depth--;
      }
      next();
    }
  }

  private @Nullable String skipBlockAndInferType() {
    accept(LBRACE);
    valueScopes.push(new HashMap<>());
    BlockDepth depth = new BlockDepth(1);
    String inferred = null;
    while (depth.value > 0 && token != EOF) {
      if (depth.value == 1) {
        if (isSeparator(token)) {
          next();
          continue;
        }
        if (token == VAL) {
          parseVals(ImmutableList.of(), false, depth);
          continue;
        }
        if (token == VAR) {
          parseVals(ImmutableList.of(), true, depth);
          continue;
        }
        ExprInfo expr = parseExprInfoInBlock(EnumSet.of(SEMI, NEWLINE, NEWLINES), depth);
        if (expr != null && expr.rawType() != null) {
          inferred = expr.rawType();
        }
        if (depth.value == 0) {
          break;
        }
        if (isSeparator(token)) {
          next();
        }
        continue;
      }
      if (token == LBRACE) {
        depth.value++;
      } else if (token == RBRACE) {
        depth.value = Math.max(0, depth.value - 1);
      }
      next();
    }
    valueScopes.pop();
    return inferred;
  }

  private void skipExpr(EnumSet<ScalaToken> stops) {
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && brace == 0 && stops.contains(token)) {
        return;
      }
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> brace++;
        case RBRACE -> brace = Math.max(0, brace - 1);
        default -> {}
      }
      next();
    }
  }

  private void skipExprInBlock(EnumSet<ScalaToken> stops, BlockDepth depth) {
    int paren = 0;
    int bracket = 0;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && depth.value == 1 && stops.contains(token)) {
        return;
      }
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> depth.value++;
        case RBRACE -> depth.value = Math.max(0, depth.value - 1);
        default -> {}
      }
      next();
      if (depth.value == 0) {
        return;
      }
    }
  }

  private String skipExprAndInferType(EnumSet<ScalaToken> stops) {
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    boolean tupleCandidate = token == LPAREN;
    int tupleCommas = 0;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && brace == 0 && stops.contains(token)) {
        break;
      }
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> brace++;
        case RBRACE -> brace = Math.max(0, brace - 1);
        case COMMA -> {
          if (tupleCandidate && paren == 1 && bracket == 0 && brace == 0) {
            tupleCommas++;
          }
        }
        default -> {}
      }
      next();
    }
    if (tupleCandidate && tupleCommas > 0) {
      return "scala/Tuple" + (tupleCommas + 1);
    }
    return null;
  }

  private boolean skipExprAndDetectParamRef(EnumSet<ScalaToken> stops, List<String> priorParams) {
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    boolean found = false;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && brace == 0 && stops.contains(token)) {
        break;
      }
      if ((token == IDENTIFIER || token == BACKQUOTED_IDENT)
          && value != null
          && priorParams.contains(value)) {
        found = true;
      }
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> brace++;
        case RBRACE -> brace = Math.max(0, brace - 1);
        default -> {}
      }
      next();
    }
    return found;
  }

  private String parseTypeText(EnumSet<ScalaToken> stops) {
    StringBuilder sb = new StringBuilder();
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    while (token != EOF) {
      if (paren == 0 && bracket == 0 && brace == 0 && stops.contains(token)) {
        break;
      }
      if (sb.length() > 0) {
        sb.append(' ');
      }
      appendTokenText(sb, token, value);
      switch (token) {
        case LPAREN -> paren++;
        case RPAREN -> paren = Math.max(0, paren - 1);
        case LBRACK -> bracket++;
        case RBRACK -> bracket = Math.max(0, bracket - 1);
        case LBRACE -> brace++;
        case RBRACE -> brace = Math.max(0, brace - 1);
        default -> {}
      }
      next();
    }
    return sb.toString().trim();
  }

  private String parseName() {
    if (token != IDENTIFIER && token != BACKQUOTED_IDENT) {
      throw error(ErrorKind.UNEXPECTED_TOKEN, token);
    }
    String name = value;
    next();
    return name;
  }

  private ImmutableList<String> parseQualId() {
    Builder<String> parts = ImmutableList.builder();
    parts.add(parseName());
    while (token == DOT) {
      next();
      parts.add(parseName());
    }
    return parts.build();
  }

  private void appendTokenText(StringBuilder sb, ScalaToken token, @Nullable String value) {
    switch (token) {
      case IDENTIFIER -> sb.append(value);
      case BACKQUOTED_IDENT -> sb.append('`').append(value).append('`');
      case INT_LITERAL,
          LONG_LITERAL,
          FLOAT_LITERAL,
          DOUBLE_LITERAL,
          CHAR_LITERAL,
          STRING_LITERAL,
          SYMBOL_LITERAL -> sb.append(value);
      default -> sb.append(token.toString());
    }
  }

  private EnumSet<ScalaToken> boundStopTokens() {
    return EnumSet.of(COMMA, RBRACK, NEWLINE, NEWLINES, SEMI, EQUALS, SUBTYPE, SUPERTYPE, VIEWBOUND,
        COLON);
  }

  private EnumSet<ScalaToken> parentStopTokens() {
    return EnumSet.of(WITH, LBRACE, RBRACE, NEWLINE, NEWLINES, SEMI, EOF);
  }

  private String currentPackageName() {
    if (packagePrefix.isEmpty()) {
      return "";
    }
    return String.join(".", packagePrefix);
  }

  private boolean isSeparator(ScalaToken token) {
    return token == SEMI || token == NEWLINE || token == NEWLINES;
  }

  private boolean isModifierToken(ScalaToken token, @Nullable String value) {
    return switch (token) {
      case ABSTRACT, FINAL, SEALED, PRIVATE, PROTECTED, OVERRIDE, IMPLICIT, LAZY -> true;
      case IDENTIFIER -> "private".equals(value) || "protected".equals(value);
      default -> false;
    };
  }

  private boolean isParamModifierToken(ScalaToken token, @Nullable String value) {
    return switch (token) {
      case IMPLICIT, VAL, VAR, PRIVATE, PROTECTED, OVERRIDE, FINAL, LAZY -> true;
      case IDENTIFIER ->
          "implicit".equals(value)
              || "val".equals(value)
              || "var".equals(value)
              || "private".equals(value)
              || "protected".equals(value);
      default -> false;
    };
  }

  private void accept(ScalaToken expected) {
    if (token != expected) {
      throw error(ErrorKind.EXPECTED_TOKEN, expected);
    }
    next();
  }

  private ScalaToken peek() {
    if (!hasPeek) {
      peekToken = lexer.next();
      peekValue = lexer.stringValue();
      peekPosition = lexer.position();
      hasPeek = true;
    }
    return peekToken;
  }

  private void next() {
    if (hasPeek) {
      token = peekToken;
      value = peekValue;
      position = peekPosition;
      hasPeek = false;
      return;
    }
    token = lexer.next();
    value = lexer.stringValue();
    position = lexer.position();
  }

  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(lexer.source(), position, kind, args);
  }
}
