/*! For license information please see b28f3685.39c1198c.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[9280],{3359:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>r,contentTitle:()=>o,default:()=>d,frontMatter:()=>i,metadata:()=>a,toc:()=>h});var l=s(4848),n=s(8453);const i={author:"Tomasz Godzik",title:"Metals v0.6.1 - Radium",authorURL:"https://twitter.com/tomekgodzik",authorImageURL:"https://avatars1.githubusercontent.com/u/3807253?s=460&v=4"},o=void 0,a={permalink:"/metals/blog/2019/06/11/radium",source:"@site/blog/2019-06-11-radium.md",title:"Metals v0.6.1 - Radium",description:'We are excited to announce the release of Metals v0.6.1, codename "Radium" \ud83c\udf89',date:"2019-06-11T00:00:00.000Z",tags:[],readingTime:3.915,hasTruncateMarker:!1,authors:[{name:"Tomasz Godzik",url:"https://twitter.com/tomekgodzik",imageURL:"https://avatars1.githubusercontent.com/u/3807253?s=460&v=4",key:null,page:null}],frontMatter:{author:"Tomasz Godzik",title:"Metals v0.6.1 - Radium",authorURL:"https://twitter.com/tomekgodzik",authorImageURL:"https://avatars1.githubusercontent.com/u/3807253?s=460&v=4"},unlisted:!1,prevItem:{title:"Metals v0.7.0 - Thorium",permalink:"/metals/blog/2019/06/28/thorium"},nextItem:{title:"Metals v0.5.1 - Mercury",permalink:"/metals/blog/2019/04/26/mercury"}},r={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Bloop upgrade",id:"bloop-upgrade",level:2},{value:"Automatic &quot;import build&quot; for Gradle, Maven and Mill",id:"automatic-import-build-for-gradle-maven-and-mill",level:2},{value:"More reliable shutdown",id:"more-reliable-shutdown",level:2},{value:"Completions freeze less",id:"completions-freeze-less",level:2},{value:"Keyword completions",id:"keyword-completions",level:2},{value:"VS Code doesn&#39;t compile projects until it is focused",id:"vs-code-doesnt-compile-projects-until-it-is-focused",level:2},{value:"Metals is now a default server in the Sublime LSP package",id:"metals-is-now-a-default-server-in-the-sublime-lsp-package",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2}];function c(e){const t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",ul:"ul",...(0,n.R)(),...e.components};return(0,l.jsxs)(l.Fragment,{children:[(0,l.jsx)(t.p,{children:'We are excited to announce the release of Metals v0.6.1, codename "Radium" \ud83c\udf89\nThe release mostly focused on adding support for the build tools Gradle, Maven\nand Mill.'}),"\n",(0,l.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Atom, Vim,\nSublime Text and Emacs. Metals is developed at the\n",(0,l.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," along with contributors from the\ncommunity."]}),"\n",(0,l.jsxs)(t.p,{children:["In this release we merged 24 PRs and closed 6 issues, full details:\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/7?closed=1",children:"https://github.com/scalameta/metals/milestone/7?closed=1"}),"."]}),"\n",(0,l.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,l.jsxs)(t.ul,{children:["\n",(0,l.jsx)(t.li,{children:'automatic "import build" for Gradle, Maven and Mill'}),"\n",(0,l.jsx)(t.li,{children:"upgraded to Bloop v1.3.2"}),"\n",(0,l.jsx)(t.li,{children:"better handling of requests that access the presentation compiler"}),"\n",(0,l.jsx)(t.li,{children:"code completions on keywords"}),"\n",(0,l.jsx)(t.li,{children:"VS Code doesn't compile projects until it is focused"}),"\n",(0,l.jsx)(t.li,{children:"Metals is now a default server in the Sublime LSP package"}),"\n"]}),"\n",(0,l.jsxs)(t.p,{children:["Check out the website and give Metals a try: ",(0,l.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"})]}),"\n",(0,l.jsx)(t.h2,{id:"bloop-upgrade",children:"Bloop upgrade"}),"\n",(0,l.jsxs)(t.p,{children:["Metals now depends on Bloop v1.3.2 instead of v1.2.5. The v1.3 release of Bloop\nhas a ton of nice improvements that benefit Metals users. For full details, see\nthe\n",(0,l.jsx)(t.a,{href:"https://github.com/scalacenter/bloop/releases/tag/v1.3.0",children:"Bloop v1.3.0 release notes"}),"."]}),"\n",(0,l.jsx)(t.h2,{id:"automatic-import-build-for-gradle-maven-and-mill",children:'Automatic "import build" for Gradle, Maven and Mill'}),"\n",(0,l.jsx)(t.p,{children:"Now, it's possible to use Metals with the build tools Gradle, Maven and Mill!\nImporting a build with Gradle, Maven and Mill now works the same way it works\nfor sbt."}),"\n",(0,l.jsx)(t.p,{children:"For more details, see the corresponding documentation for each build tool:"}),"\n",(0,l.jsxs)(t.ul,{children:["\n",(0,l.jsx)(t.li,{children:(0,l.jsx)(t.a,{href:"http://scalameta.org/metals/docs/build-tools/overview.html",children:"Overview"})}),"\n",(0,l.jsx)(t.li,{children:(0,l.jsx)(t.a,{href:"http://scalameta.org/metals/docs/build-tools/gradle.html",children:"Gradle"})}),"\n",(0,l.jsx)(t.li,{children:(0,l.jsx)(t.a,{href:"http://scalameta.org/metals/docs/build-tools/maven.html",children:"Maven"})}),"\n",(0,l.jsx)(t.li,{children:(0,l.jsx)(t.a,{href:"http://scalameta.org/metals/docs/build-tools/mill.html",children:"Mill"})}),"\n"]}),"\n",(0,l.jsx)(t.h2,{id:"more-reliable-shutdown",children:"More reliable shutdown"}),"\n",(0,l.jsx)(t.p,{children:"An issue where the Metals process could continue running even after closing the\neditor has now been fixed."}),"\n",(0,l.jsx)(t.h2,{id:"completions-freeze-less",children:"Completions freeze less"}),"\n",(0,l.jsx)(t.p,{children:"An issue where Metals could get stuck in an infinite loop using 100% CPU while\nresponding to completions has now been fixed."}),"\n",(0,l.jsx)(t.h2,{id:"keyword-completions",children:"Keyword completions"}),"\n",(0,l.jsxs)(t.p,{children:["Previously, Metals did not complete keywords like ",(0,l.jsx)(t.code,{children:"import"}),", ",(0,l.jsx)(t.code,{children:"new"}),", ",(0,l.jsx)(t.code,{children:"lazy val"})," or\n",(0,l.jsx)(t.code,{children:"trait"}),". Language keywords are now included in the auto-completions in most\ncases."]}),"\n",(0,l.jsx)(t.pre,{children:(0,l.jsx)(t.code,{className:"language-scala",children:"object Main {\n    // Before\n    impo<COMPLETE>\n    // After\n    import\n}\n"})}),"\n",(0,l.jsx)(t.p,{children:"Keywords are suggested based on the context (e.g. you won't see throw suggested\nif the cursor is not inside a declaration)"}),"\n",(0,l.jsxs)(t.p,{children:["The only keywords that are not completed are: ",(0,l.jsx)(t.code,{children:"extends"}),", ",(0,l.jsx)(t.code,{children:"finally"}),", ",(0,l.jsx)(t.code,{children:"with"}),",\n",(0,l.jsx)(t.code,{children:"forSome"}),", ",(0,l.jsx)(t.code,{children:"catch"})," and ",(0,l.jsx)(t.code,{children:"finally"}),"."]}),"\n",(0,l.jsx)(t.h2,{id:"vs-code-doesnt-compile-projects-until-it-is-focused",children:"VS Code doesn't compile projects until it is focused"}),"\n",(0,l.jsx)(t.p,{children:"Previously, Metals would trigger compilation in the background even if the VS\nCode window was not focused. For example, switching git branches in a separate\nterminal window would still trigger compilation in Metals. Now, Metals waits\nuntil the VS Code window is focused to trigger compilation."}),"\n",(0,l.jsxs)(t.p,{children:["This feature is implemented as a LSP extension and is currently only supported\nby VS Code. For details on how to implement this extension for another editor,\nsee the\n",(0,l.jsx)(t.a,{href:"http://scalameta.org/metals/docs/integrations/new-editor#metals-windowstatedidchange",children:"documentation on integrating a new text editor"}),"."]}),"\n",(0,l.jsx)(t.h2,{id:"metals-is-now-a-default-server-in-the-sublime-lsp-package",children:"Metals is now a default server in the Sublime LSP package"}),"\n",(0,l.jsxs)(t.p,{children:["Metals is now a default server for Scala source files in the LSP package, see\n",(0,l.jsx)(t.a,{href:"https://github.com/tomv564/LSP/pull/571",children:"tomv564/LSP#571"}),". This greatly\nsimplifies the installation steps for Sublime Text users."]}),"\n",(0,l.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,l.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release!"}),"\n",(0,l.jsx)(t.pre,{children:(0,l.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.5.1..v0.6.1\n\xd3lafur P\xe1ll Geirsson\nAdam Gajek\nTomasz Godzik\nGabriele Petronella\nAyoub Benali\nCody Allen\nEvgeny Kurnevsky\nJeffrey Lau\nMarek \u017barnowski\nMarkus Hauck\nGerman Greiner\n"})}),"\n",(0,l.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,l.jsxs)(t.ul,{children:["\n",(0,l.jsxs)(t.li,{children:["Avoid inifinite loop when shortening types\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/751",children:"#751"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Move releasing info to the website\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/748",children:"#748"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update bloop to 1.3.2 ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/747",children:"#747"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Split build tool and executable name to show properly in output\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/746",children:"#746"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update bloop to 1.3.0 and BSP to 2.0.0-M4\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/745",children:"#745"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add better hint in the doctor for Maven workspaces\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/744",children:"#744"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update emacs docs as scala is supported by lsp-mode directly now\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/741",children:"#741"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Remove Scalafix from pre-push git hook.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/740",children:"#740"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Setup git hooks for scalafmt and scalafix.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/738",children:"#738"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add automatic Mill import to metals\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/737",children:"#737"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Improve handling of requests that access the presentation compiler.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/736",children:"#736"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Enforce scala version for embedded bloop server\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/735",children:"#735"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Ensure build server shuts down before starting new build connection.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/731",children:"#731"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix blogpost typo ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/730",children:"#730"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/zoonfafer",children:"zoonfafer"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Miscellaneous polish fixes\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/729",children:"#729"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Implement textDocument/codeLens to run main function\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/728",children:"#728"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/marek1840",children:"marek1840"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix newlines in process output.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/727",children:"#727"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update sublime doc ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/726",children:"#726"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ayoub-benali",children:"ayoub-benali"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix transitive scala library dependency in Gradle builds.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/725",children:"#725"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Adds maven integration to metals\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/722",children:"#722"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Use CocRequestAsync in Vim docs\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/717",children:"#717"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ceedubs",children:"ceedubs"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Set timeout for shutdown procedure, fixes #715.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/716",children:"#716"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add keyword completion ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/712",children:"#712"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/markus1189",children:"markus1189"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Make bloop versions customizable via server properties.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/710",children:"#710"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Pause compile-on-save while the editor window is not focused\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/709",children:"#709"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/agajek",children:"agajek"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add gradle support to imports in metals and refactor build tool support.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/694",children:"#694"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,n.R)(),...e.components};return t?(0,l.jsx)(t,{...e,children:(0,l.jsx)(c,{...e})}):c(e)}},1020:(e,t,s)=>{var l=s(6540),n=Symbol.for("react.element"),i=Symbol.for("react.fragment"),o=Object.prototype.hasOwnProperty,a=l.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,r={key:!0,ref:!0,__self:!0,__source:!0};function h(e,t,s){var l,i={},h=null,c=null;for(l in void 0!==s&&(h=""+s),void 0!==t.key&&(h=""+t.key),void 0!==t.ref&&(c=t.ref),t)o.call(t,l)&&!r.hasOwnProperty(l)&&(i[l]=t[l]);if(e&&e.defaultProps)for(l in t=e.defaultProps)void 0===i[l]&&(i[l]=t[l]);return{$$typeof:n,type:e,key:h,ref:c,props:i,_owner:a.current}}t.Fragment=i,t.jsx=h,t.jsxs=h},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>o,x:()=>a});var l=s(6540);const n={},i=l.createContext(n);function o(e){const t=l.useContext(i);return l.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:o(e.components),l.createElement(i.Provider,{value:t},e.children)}}}]);