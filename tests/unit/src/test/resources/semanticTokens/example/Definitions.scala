<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*namespace*/.<<derivation>>/*namespace*/.<<deriveDecoder>>/*variable,readonly*/
<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*namespace*/.<<derivation>>/*namespace*/.<<deriveEncoder>>/*variable,readonly*/

<<class>>/*keyword*/ <<Definitions>>/*class*/ {
  <<Predef>>/*class*/.<<any2stringadd>>/*method,deprecated*/(<<1>>/*number*/)
  <<List>>/*class*/[
    <<java>>/*namespace*/.<<util>>/*namespace*/.<<Map>>/*class*/.<<Entry>>/*interface,abstract*/[
      <<java>>/*namespace*/.<<lang>>/*namespace*/.<<Integer>>/*class*/,
      <<java>>/*namespace*/.<<lang>>/*namespace*/.<<Double>>/*class*/,
    ]
  ](
    <<elems>>/*parameter,readonly*/ = <<null>>/*keyword*/
  )
  <<println>>/*method*/(<<deriveDecoder>>/*method*/[<<MacroAnnotation>>/*class*/])
  <<println>>/*method*/(<<deriveEncoder>>/*method*/[<<MacroAnnotation>>/*class*/])
}