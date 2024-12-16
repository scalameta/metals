"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["2058"],{21233:function(e,t,i){i.r(t),i.d(t,{assets:function(){return o},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return l},metadata:function(){return s},toc:function(){return h}});var s=i(34567),n=i(85893),a=i(50065);let l={authors:"tgodzik",title:"Metals v0.7.5 - Thorium"},r=void 0,o={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Add auto-fill option to case classes",id:"add-auto-fill-option-to-case-classes",level:2},{value:"Deduce values for named parameters completions",id:"deduce-values-for-named-parameters-completions",level:2},{value:"Multiple fixes after adding support for Scala 2.12.9",id:"multiple-fixes-after-adding-support-for-scala-2129",level:2},{value:"Fixes for automatic addition of <code>|</code> in multiline strings",id:"fixes-for-automatic-addition-of--in-multiline-strings",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.7.5 (2019-09-12)",id:"v075-2019-09-12",level:2}];function c(e){let t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,a.a)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:'We are excited to announce the release of Metals v0.7.5 - codename "Thorium" \uD83C\uDF89\nThe release includes couple of bug fixes for 0.7.2, which were present in some\nrare scenarios. We also added a new experimental feature, which we would be\nhappy to get feedback about.'}),"\n",(0,n.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Atom, Vim,\nSublime Text and Emacs. Metals is developed at the\n",(0,n.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,n.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nalong with contributors from the community."]}),"\n",(0,n.jsxs)(t.p,{children:["In this release we merged 12 PRs and closed 6 issues, full details:\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/17?closed=1",children:"https://github.com/scalameta/metals/milestone/17?closed=1"})]}),"\n",(0,n.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,n.jsxs)(t.p,{children:["Check out the website and give Metals a try: ",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"})]}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"add auto-fill option to case classes"}),"\n",(0,n.jsx)(t.li,{children:"deduce values for named parameters completions"}),"\n",(0,n.jsx)(t.li,{children:"multiple fixes after adding support for Scala 2.12.9"}),"\n",(0,n.jsxs)(t.li,{children:["fixes for automatic addition of ",(0,n.jsx)(t.code,{children:"|"})," in multiline strings"]}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"add-auto-fill-option-to-case-classes",children:"Add auto-fill option to case classes"}),"\n",(0,n.jsx)(t.p,{children:"Whenever we invoke a method and try completions inside the brackets there is an\nadditional option to allow Metals to deduce what should be filled for each\nparameter."}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/8pCiMqE.gif",alt:"auto-fill"})}),"\n",(0,n.jsx)(t.p,{children:"This algorithm checks if there exists a value in scope with the same type as the\nparameters' and then works according to the rules below:"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:'1. If there is only one candidate, use it.\n2. If there is more than one, give user an alternative to choose one of them.\n3. Otherwise use use "???".\n'})}),"\n",(0,n.jsx)(t.p,{children:"This feature also uses the snippets syntax to allow to quickly go over the\nauto-filled parameters and choose or replace the auto-filled values."}),"\n",(0,n.jsx)(t.h2,{id:"deduce-values-for-named-parameters-completions",children:"Deduce values for named parameters completions"}),"\n",(0,n.jsx)(t.p,{children:"Leveraging the work that went into the previous feature, we were also able to\nprovide additional support for concrete values in named parameter completions."}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/m11xixy.gif",alt:"deduce"})}),"\n",(0,n.jsx)(t.p,{children:"We generate completion item for each value that fits the type of the parameter."}),"\n",(0,n.jsx)(t.h2,{id:"multiple-fixes-after-adding-support-for-scala-2129",children:"Multiple fixes after adding support for Scala 2.12.9"}),"\n",(0,n.jsx)(t.p,{children:"After updating Metals to work with Scala 2.12.9 it turned out that completions\nand hover didn't work correctly and due to the presentation compiler crashing in\nthe following scenarios:"}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"whenever an older version of SemanticDB plugin was used as was the case with\nMill"}),"\n",(0,n.jsx)(t.li,{children:"when using Scala 2.12.8 without SemanticDB plugin"}),"\n"]}),"\n",(0,n.jsx)(t.p,{children:"Both issues have now been fixed and additionally we updated the default Mill\nversion to 0.5.1, which works correctly with Scala 2.12.9."}),"\n",(0,n.jsxs)(t.h2,{id:"fixes-for-automatic-addition-of--in-multiline-strings",children:["Fixes for automatic addition of ",(0,n.jsx)(t.code,{children:"|"})," in multiline strings"]}),"\n",(0,n.jsxs)(t.p,{children:["We now make sure that ",(0,n.jsx)(t.code,{children:"stripMargin"})," exists when adding ",(0,n.jsx)(t.code,{children:"|"})," automatically in\nmultiline strings. This could cause issues in some scenarios, where multiline\nstring contained ",(0,n.jsx)(t.code,{children:"|"}),", but it was not only used for formatting."]}),"\n",(0,n.jsx)(t.p,{children:"Additionally, we fixed the feature to also work in interpolated strings, which\nwas missing in the previous release."}),"\n",(0,n.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release!"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.7.2..v0.7.5\nTomasz Godzik\nGabriele Petronella\n\u0141ukasz Byczy\u0144ski\nOlafur Pall Geirsson\n"})}),"\n",(0,n.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,n.jsxs)(t.h2,{id:"v075-2019-09-12",children:[(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.7.5",children:"v0.7.5"})," (2019-09-12)"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.7.2...v0.7.5",children:"Full Changelog"})}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsxs)(t.li,{children:["Revert sbt version to 1.2.8\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/915",children:"#915"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Upgrade to latest sbt-sonatype for faster and more stable releases\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/912",children:"#912"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update with new Eclipse capabilities\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/911",children:"#911"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/mundacho",children:"mundacho"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Check for stripMargin when adding |\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/904",children:"#904"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add additional tips & tricks to the sublime text documentation\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/906",children:"#906"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/LukaszByczynski",children:"LukaszByczynski"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update sbt to 1.3.0 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/905",children:"#905"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix more possible presentation compiler classpath issues\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/901",children:"#901"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update Mill to 0.5.1 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/899",children:"#899"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update website overview and add information about Eclipse\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/894",children:"#894"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Retry with clean compiler after ShutdownReq\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/898",children:"#898"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add automatic value completions on named arguments\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/827",children:"#827"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix links to merged PRs in blogpost\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/896",children:"#896"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add release notes for v0.7.2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/886",children:"#886"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,a.a)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(c,{...e})}):c(e)}},50065:function(e,t,i){i.d(t,{Z:function(){return r},a:function(){return l}});var s=i(67294);let n={},a=s.createContext(n);function l(e){let t=s.useContext(a);return s.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:l(e.components),s.createElement(a.Provider,{value:t},e.children)}},34567:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2019/09/12/thorium","source":"@site/blog/2019-09-12-thorium.md","title":"Metals v0.7.5 - Thorium","description":"We are excited to announce the release of Metals v0.7.5 - codename \\"Thorium\\" \uD83C\uDF89","date":"2019-09-12T00:00:00.000Z","tags":[],"readingTime":3.04,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v0.7.5 - Thorium"},"unlisted":false,"prevItem":{"title":"Metals v0.7.6 - Thorium","permalink":"/metals/blog/2019/09/23/thorium"},"nextItem":{"title":"Metals v0.7.2 - Thorium","permalink":"/metals/blog/2019/09/02/thorium"}}')}}]);