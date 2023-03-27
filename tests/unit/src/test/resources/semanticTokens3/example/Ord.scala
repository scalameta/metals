<<package>>/*keyword*/ <<example>>/*namespace*/

<<trait>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/]:
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter,readonly*/: <<T>>/*typeParameter,abstract*/, <<y>>/*parameter,readonly*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/

<<given>>/*keyword*/ <<intOrd>>/*class*/: <<Ord>>/*interface,abstract*/[<<Int>>/*class,abstract*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter,readonly*/: <<Int>>/*class,abstract*/, <<y>>/*parameter,readonly*/: <<Int>>/*class,abstract*/) =
    <<if>>/*keyword*/ <<x>>/*parameter,readonly*/ <<<>>/*method*/ <<y>>/*parameter,readonly*/ <<then>>/*keyword*/ -<<1>>/*number*/ <<else>>/*keyword*/ <<if>>/*keyword*/ <<x>>/*parameter,readonly*/ <<>>>/*method*/ <<y>>/*parameter,readonly*/ <<then>>/*keyword*/ +<<1>>/*number*/ <<else>>/*keyword*/ <<0>>/*number*/

<<given>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<String>>/*type*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter,readonly*/: <<String>>/*type*/, <<y>>/*parameter,readonly*/: <<String>>/*type*/) =
    <<x>>/*parameter,readonly*/.<<compare>>/*method*/(<<y>>/*parameter,readonly*/)