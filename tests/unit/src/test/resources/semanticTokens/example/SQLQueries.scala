<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<SQLQueries>>/*class*/ {
    <<implicit>>/*modifier*/ <<class>>/*keyword*/ <<SQLStringContext>>/*class*/(<<sc>>/*variable,declaration,readonly*/: <<StringContext>>/*class*/) {
        <<def>>/*keyword*/ <<sql>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Any>>/*class,abstract*/*): <<String>>/*type*/ = <<sc>>/*variable,readonly*/.<<s>>/*method*/(<<args>>/*parameter,readonly*/: <<_>>/*variable,readonly*/*)
    }

    <<val>>/*keyword*/ <<createTableQuery>>/*variable,definition,readonly*/ = <<sql>>/*keyword*/<<""">>/*string*/
        <<CREATE>>/*keyword*/ <<TABLE>>/*keyword*/ <<users>>/*variable*/ (
            <<id>>/*variable*/ <<INT>>/*keyword*/ <<PRIMARY>>/*keyword*/ <<KEY>>/*keyword*/,
            <<name>>/*variable*/ <<VARCHAR>>/*function*/(<<100>>/*number*/),
            <<age>>/*variable*/ <<DECIMAL>>/*function*/(<<3>>/*number*/, <<1>>/*number*/),
            <<created_at>>/*variable*/ <<TIMESTAMP>>/*keyword*/
        )
        <<""">>/*string*/

    <<val>>/*keyword*/ <<selectQuery>>/*variable,definition,readonly*/ = <<sql>>/*keyword*/<<""">>/*string*/
        <<SELECT>>/*keyword*/ <<name>>/*variable*/, <<age>>/*variable*/
        <<FROM>>/*keyword*/ <<users>>/*variable*/
        <<WHERE>>/*keyword*/ <<age>>/*variable*/ <<>>>/*operator*/ <<30.5>>/*number*/
        <<""">>/*string*/

    <<val>>/*keyword*/ <<insertQuery>>/*variable,definition,readonly*/ = <<sql>>/*keyword*/<<""">>/*string*/
        <<INSERT>>/*keyword*/ <<INTO>>/*keyword*/ <<users>>/*variable*/ (<<id>>/*variable*/, <<name>>/*variable*/, <<age>>/*variable*/, <<created_at>>/*variable*/)
        <<VALUES>>/*keyword*/ (<<1>>/*number*/, <<'John Doe'>>/*string*/, <<25>>/*number*/, <<CURRENT_TIMESTAMP>>/*variable*/)
        <<""">>/*string*/
}