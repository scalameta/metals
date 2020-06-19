package tests

import scala.meta.internal.docstrings.MarkdownGenerator

import munit.Location

class JavadocSuite extends BaseSuite {

  def check(name: String, original: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(name) {
      val obtained = MarkdownGenerator.toMarkdown(original).mkString
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "codelink",
    """/**
      |* Returns the problems (errors/warnings/infos) that were generated in the
      |* last incremental compilation, be it successful or not. See
      |* [[previousProblemsFromSuccessfulCompilation]] for an explanation of why
      |* these problems are important for the bloop compilation.
      |*
      |* @see [[previousProblemsFromSuccessfulCompilation]]
      |*/
    """.stripMargin,
    """Returns the problems (errors/warnings/infos) that were generated in the
      |last incremental compilation, be it successful or not. See
      |[previousProblemsFromSuccessfulCompilation](previousProblemsFromSuccessfulCompilation) for an explanation of why
      |these problems are important for the bloop compilation.
      |
      |
      |**See**
      |- [previousProblemsFromSuccessfulCompilation](previousProblemsFromSuccessfulCompilation)""".stripMargin
  )

  check(
    "escapee",
    """/**
      |* Replaces the first substring of this string that matches the given <a
      |* href="../util/regex/Pattern.html#sum">regular expression</a> with the
      |* given replacement.
      |*
      |* <p> An invocation of this method of the form
      |* <i>str</i>{@code .replaceFirst(}<i>regex</i>{@code ,} <i>repl</i>{@code )}
      |* yields exactly the same result as the expression
      |*
      |* <blockquote>
      |* <code>
      |* {@link java.util.regex.Pattern}.{@link
      |* java.util.regex.Pattern#compile compile}(<i>regex</i>).{@link
      |* java.util.regex.Pattern#matcher(java.lang.CharSequence) matcher}(<i>str</i>).{@link
      |* java.util.regex.Matcher#replaceFirst replaceFirst}(<i>repl</i>)
      |* </code>
      |* </blockquote>
      |*
      |* <p>
      |* Note that backslashes ({@code \}) and dollar signs ({@code $}) in the
      |* replacement string may cause the results to be different than if it were
      |* being treated as a literal replacement string; see
      |* {@link java.util.regex.Matcher#replaceFirst}.
      |* Use {@link java.util.regex.Matcher#quoteReplacement} to suppress the special
      |* meaning of these characters, if desired.
      |*/
     """.stripMargin,
    """
      |Replaces the first substring of this string that matches the given [regular expression]() with the
      |given replacement.
      |
      | An invocation of this method of the form
      |*str*`.replaceFirst(`*regex*`,` *repl*`)`
      |yields exactly the same result as the expression
      |
      |Note that backslashes (`\`) and dollar signs (`$`) in the
      |replacement string may cause the results to be different than if it were
      |being treated as a literal replacement string; see
      |[java.util.regex.Matcher#replaceFirst](java.util.regex.Matcher#replaceFirst).
      |Use [java.util.regex.Matcher#quoteReplacement](java.util.regex.Matcher#quoteReplacement) to suppress the special
      |meaning of these characters, if desired.
    """.stripMargin
  )

  check(
    "table",
    s"""
       |<table>
       |<th>foo</th>
       |<tr><td>bar</td></tr>
       |</table>
       """.stripMargin,
    ""
  )

  check(
    "basic",
    "/** a {@code int} value */",
    "a `int` value"
  )

  check(
    "basicHtml",
    """/** a {@code int} value <p><code>foo</code></p><a href="http://www.cnn.com">CNN</a> */""",
    "a `int` value `foo`\n\n[CNN](http://www.cnn.com)"
  )

  check(
    name = "javadoc",
    original = """/**
                 | * A class representing a window on the screen.
                 | * For example:
                 | * <pre>
                 | *    Window win = new Window(parent);
                 | *    win.show();
                 | * </pre>
                 | *
                 | * @author  Sami Shaio
                 | * @version 1.15, 13 Dec 2006
                 | * @see     java.awt.BaseWindow
                 | * @see     java.awt.Button
                 | */""".stripMargin,
    s"""|A class representing a window on the screen.
        |For example:
        |
        |```
        |Window win = new Window(parent);
        |win.show();
        |```
        |**See**
        |- [java.awt.BaseWindow](java.awt.BaseWindow)
        |- [java.awt.Button](java.awt.Button)
     """.stripMargin
  )
  check(
    "method",
    s"""/**
       | * Returns an Image object that can then be painted on the screen.
       | * The url argument must specify an absolute {@link URL}. The name
       | * argument is a specifier that is relative to the url argument.
       | * <p>
       | * This method always returns immediately, whether or not the
       | * image exists. When this applet attempts to draw the image on
       | * the screen, the data will be loaded. The graphics primitives
       | * that draw the image will incrementally paint on the screen.
       |
       | {@linkplain #javadoc}
       |
       | *
       | * @param  url  an absolute URL giving the base location of the image
       | * @param  name the location of the image, relative to the url argument
       | * @return      the image at the specified URL
       | * @throws IOException when stuff hapend
       | * @see         Image
       | */""".stripMargin,
    s"""|Returns an Image object that can then be painted on the screen.
        |The url argument must specify an absolute [URL](URL). The name
        |argument is a specifier that is relative to the url argument.
        |
        |This method always returns immediately, whether or not the
        |image exists. When this applet attempts to draw the image on
        |the screen, the data will be loaded. The graphics primitives
        |that draw the image will incrementally paint on the screen.
        |
        |[#javadoc](#javadoc)
        |
        |**Parameters**
        |- `url`: an absolute URL giving the base location of the image
        |- `name`: the location of the image, relative to the url argument
        |
        |**Returns:** the image at the specified URL
        |
        |**Throws**
        |- `IOException`: 
        |
        |**See**
        |- [Image](Image)""".stripMargin
  )

  check(
    "complex",
    """/**
      |   * Constructs a Gson object with default configuration. The default configuration has the
      |   * following settings:
      |   * <ul>
      |   *   <li>The JSON generated by <code>toJson</code> methods is in compact representation. This
      |   *   means that all the unneeded white-space is removed. You can change this behavior with
      |   *   {@link GsonBuilder#setPrettyPrinting()}. </li>
      |   *   <li>The generated JSON omits all the fields that are null. Note that nulls in arrays are
      |   *   kept as is since an array is an ordered list. Moreover, if a field is not null, but its
      |   *   generated JSON is empty, the field is kept. You can configure Gson to serialize null values
      |   *   by setting {@link GsonBuilder#serializeNulls()}.</li>
      |   *   <li>Gson provides default serialization and deserialization for Enums, {@link Map},
      |   *   {@link java.net.URL}, {@link java.net.URI}, {@link java.util.Locale}, {@link java.util.Date},
      |   *   {@link java.math.BigDecimal}, and {@link java.math.BigInteger} classes. If you would prefer
      |   *   to change the default representation, you can do so by registering a type adapter through
      |   *   {@link GsonBuilder#registerTypeAdapter(Type, Object)}. </li>
      |   *   <li>The default Date format is same as {@link java.text.DateFormat#DEFAULT}. This format
      |   *   ignores the millisecond portion of the date during serialization. You can change
      |   *   this by invoking {@link GsonBuilder#setDateFormat(int)} or
      |   *   {@link GsonBuilder#setDateFormat(String)}. </li>
      |   *   <li>By default, Gson ignores the {@link com.google.gson.annotations.Expose} annotation.
      |   *   You can enable Gson to serialize/deserialize only those fields marked with this annotation
      |   *   through {@link GsonBuilder#excludeFieldsWithoutExposeAnnotation()}. </li>
      |   *   <li>By default, Gson ignores the {@link com.google.gson.annotations.Since} annotation. You
      |   *   can enable Gson to use this annotation through {@link GsonBuilder#setVersion(double)}.</li>
      |   *   <li>The default field naming policy for the output Json is same as in Java. So, a Java class
      |   *   field <code>versionNumber</code> will be output as <code>&quot;versionNumber&quot;</code> in
      |   *   Json. The same rules are applied for mapping incoming Json to the Java classes. You can
      |   *   change this policy through {@link GsonBuilder#setFieldNamingPolicy(FieldNamingPolicy)}.</li>
      |   *   <li>By default, Gson excludes <code>transient</code> or <code>static</code> fields from
      |   *   consideration for serialization and deserialization. You can change this behavior through
      |   *   {@link GsonBuilder#excludeFieldsWithModifiers(int...)}.</li>
      |   * </ul>
      |   */""".stripMargin,
    """|Constructs a Gson object with default configuration. The default configuration has the
       |following settings:
       |
       |- The JSON generated by- `toJson`- methods is in compact representation. This
       |  means that all the unneeded white-space is removed. You can change this behavior with
       |  [GsonBuilder#setPrettyPrinting()](GsonBuilder#setPrettyPrinting()).
       |- The generated JSON omits all the fields that are null. Note that nulls in arrays are
       |  kept as is since an array is an ordered list. Moreover, if a field is not null, but its
       |  generated JSON is empty, the field is kept. You can configure Gson to serialize null values
       |  by setting [GsonBuilder#serializeNulls()](GsonBuilder#serializeNulls()).
       |- Gson provides default serialization and deserialization for Enums, [Map](Map),
       |  [java.net.URL](java.net.URL), [java.net.URI](java.net.URI), [java.util.Locale](java.util.Locale), [java.util.Date](java.util.Date),
       |  [java.math.BigDecimal](java.math.BigDecimal), and [java.math.BigInteger](java.math.BigInteger) classes. If you would prefer
       |  to change the default representation, you can do so by registering a type adapter through
       |  [GsonBuilder#registerTypeAdapter(Type, Object)](GsonBuilder#registerTypeAdapter(Type, Object)).
       |- The default Date format is same as [java.text.DateFormat#DEFAULT](java.text.DateFormat#DEFAULT). This format
       |  ignores the millisecond portion of the date during serialization. You can change
       |  this by invoking [GsonBuilder#setDateFormat(int)](GsonBuilder#setDateFormat(int)) or
       |  [GsonBuilder#setDateFormat(String)](GsonBuilder#setDateFormat(String)).
       |- By default, Gson ignores the [com.google.gson.annotations.Expose](com.google.gson.annotations.Expose) annotation.
       |  You can enable Gson to serialize/deserialize only those fields marked with this annotation
       |  through [GsonBuilder#excludeFieldsWithoutExposeAnnotation()](GsonBuilder#excludeFieldsWithoutExposeAnnotation()).
       |- By default, Gson ignores the [com.google.gson.annotations.Since](com.google.gson.annotations.Since) annotation. You
       |  can enable Gson to use this annotation through [GsonBuilder#setVersion(double)](GsonBuilder#setVersion(double)).
       |- The default field naming policy for the output Json is same as in Java. So, a Java class
       |  field- `versionNumber`- will be output as- `"versionNumber"`- in
       |  Json. The same rules are applied for mapping incoming Json to the Java classes. You can
       |  change this policy through [GsonBuilder#setFieldNamingPolicy(FieldNamingPolicy)](GsonBuilder#setFieldNamingPolicy(FieldNamingPolicy)).
       |- By default, Gson excludes- `transient`- or- `static`- fields from
       |  consideration for serialization and deserialization. You can change this behavior through
       |  [GsonBuilder#excludeFieldsWithModifiers(int...)](GsonBuilder#excludeFieldsWithModifiers(int...)).""".stripMargin
  )

  check(
    "nested list",
    s"""
       |/**
       | * <ul>
       | *   <li>some element</li>
       | *   <li>
       | * <ul>
       | *       <li>some other element</li>
       | *       <li><ul>
       | *           <li>yet some other element</li>
       | *         </ul>
       | *       </li>
       | *     </ul>
       | *   </li>
       | * </ul>
       | */""".stripMargin,
    s"""|- some element
        |\t- some other element
        |\t\t- yet some other element""".stripMargin
  )

}
