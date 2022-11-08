<<package>>/*keyword*/ <<example>>/*namespace*/

<<case>>/*keyword*/ <<class>>/*keyword*/ <<User>>/*class*/(
    <<name>>/*variable,readonly*/: <<String>>/*type*/ = {
      <<// assert default values have occurrences>>/*comment*/
      <<Map>>/*variable,readonly*/.<<toString>>/*method*/
    }
)
<<object>>/*keyword*/ <<NamedArguments>>/*class*/ {
  <<final>>/*modifier*/ <<val>>/*keyword*/ <<susan>>/*variable,readonly*/ = <<"Susan">>/*string*/
  <<val>>/*keyword*/ <<user1>>/*variable,readonly*/ =
    <<User>>/*class*/
      .<<apply>>/*method*/(
        <<name>>/*parameter*/ = <<"John">>/*string*/
      )
  <<val>>/*keyword*/ <<user2>>/*variable,readonly*/: <<User>>/*class*/ =
    <<User>>/*class*/(
      <<name>>/*parameter*/ = <<susan>>/*variable,readonly*/
    ).<<copy>>/*method*/(
      <<name>>/*parameter*/ = <<susan>>/*variable,readonly*/
    )

  <<// anonymous classes>>/*comment*/
  <<@>>/*keyword*/<<deprecated>>/*class*/(
    <<message>>/*parameter*/ = <<"a">>/*string*/,
    <<since>>/*parameter*/ = <<susan>>/*variable,readonly*/,
  ) <<def>>/*keyword*/ <<b>>/*method,deprecated*/ = <<1>>/*number*/

  <<// vararg>>/*comment*/
  <<List>>/*variable,readonly*/(
    <<elems>>/*parameter*/ = <<2>>/*number*/
  )

}