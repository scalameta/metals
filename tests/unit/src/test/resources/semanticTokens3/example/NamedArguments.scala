<<package>>/*keyword*/ <<example>>/*namespace*/

<<case>>/*keyword*/ <<class>>/*keyword*/ <<User>>/*class*/(
    <<name>>/*variable,declaration,readonly*/: <<String>>/*type*/ = {
      <<// assert default values have occurrences>>/*comment*/
      <<Map>>/*class*/.<<toString>>/*method*/
    }
)
<<object>>/*keyword*/ <<NamedArguments>>/*class*/ {
  <<final>>/*modifier*/ <<val>>/*keyword*/ <<susan>>/*variable,definition,readonly*/ = <<"Susan">>/*string*/
  <<val>>/*keyword*/ <<user1>>/*variable,definition,readonly*/ =
    <<User>>/*class*/
      .<<apply>>/*method*/(
        <<name>>/*parameter,readonly*/ = <<"John">>/*string*/
      )
  <<val>>/*keyword*/ <<user2>>/*variable,definition,readonly*/: <<User>>/*class*/ =
    <<User>>/*class*/(
      <<name>>/*parameter,readonly*/ = <<susan>>/*variable,readonly*/
    ).<<copy>>/*method*/(
      <<name>>/*parameter,readonly*/ = <<susan>>/*variable,readonly*/
    )

  <<// anonymous classes>>/*comment*/
  <<@>>/*keyword*/<<deprecated>>/*class*/(
    <<message>>/*parameter,readonly*/ = <<"a">>/*string*/,
    <<since>>/*parameter,readonly*/ = <<susan>>/*variable,readonly*/,
  ) <<def>>/*keyword*/ <<b>>/*method,definition,deprecated*/ = <<1>>/*number*/

  <<// vararg>>/*comment*/
  <<List>>/*class*/(
    elems = <<2>>/*number*/
  )

}