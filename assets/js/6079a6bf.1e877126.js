/*! For license information please see 6079a6bf.1e877126.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[5180],{7817:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>i,contentTitle:()=>a,default:()=>h,frontMatter:()=>n,metadata:()=>o,toc:()=>c});var r=s(4848),l=s(8453);const n={author:"\xd3lafur P\xe1ll Geirsson",title:"Metals v0.4.4 - Tin",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},a=void 0,o={permalink:"/metals/blog/2019/02/01/tin",source:"@site/blog/2019-02-01-tin.md",title:"Metals v0.4.4 - Tin",description:"Metals is a language server for Scala that works with VS Code, Atom, Vim,",date:"2019-02-01T00:00:00.000Z",tags:[],readingTime:1.965,hasTruncateMarker:!0,authors:[{name:"\xd3lafur P\xe1ll Geirsson",url:"https://twitter.com/olafurpg",imageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4",key:null,page:null}],frontMatter:{author:"\xd3lafur P\xe1ll Geirsson",title:"Metals v0.4.4 - Tin",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},unlisted:!1,prevItem:{title:"Metals v0.5.0 - Mercury",permalink:"/metals/blog/2019/04/12/mercury"},nextItem:{title:"Metals v0.4.0 - Tin",permalink:"/metals/blog/2019/01/24/tin"}},i={authorsImageUrls:[void 0]},c=[{value:"Metals server",id:"metals-server",level:2},{value:"Improved code navigation",id:"improved-code-navigation",level:3},{value:"Empty source directories are no longer created",id:"empty-source-directories-are-no-longer-created",level:3},{value:"Multiple workspace folders",id:"multiple-workspace-folders",level:3},{value:"Avoid <code>metals.sbt</code> compile errors on old sbt versions",id:"avoid-metalssbt-compile-errors-on-old-sbt-versions",level:3},{value:"Visual Studio Code",id:"visual-studio-code",level:2},{value:"<code>JAVA_OPTS</code>",id:"java_opts",level:3},{value:"Sublime Text",id:"sublime-text",level:2},{value:"Emacs",id:"emacs",level:2},{value:"Merged PRs",id:"merged-prs",level:2}];function d(e){const t={a:"a",code:"code",h2:"h2",h3:"h3",li:"li",p:"p",ul:"ul",...(0,l.R)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Atom, Vim,\nSublime Text and Emacs. Metals is developed at the\n",(0,r.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," along with contributors from the\ncommunity."]}),"\n",(0,r.jsx)(t.h2,{id:"metals-server",children:"Metals server"}),"\n",(0,r.jsxs)(t.p,{children:["In this milestone we merged 5 PRs, full details:\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/5?closed=1",children:"https://github.com/scalameta/metals/milestone/5?closed=1"}),"."]}),"\n",(0,r.jsx)(t.h3,{id:"improved-code-navigation",children:"Improved code navigation"}),"\n",(0,r.jsx)(t.p,{children:'Several "goto definition" and "find references" bugs have been fixed in this\nrelease. In particular, code navigation should work more reliably now for the\nfollowing language features'}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsx)(t.li,{children:"for comprehensions with multiple assignments and guards"}),"\n",(0,r.jsx)(t.li,{children:"eta-expanded methods and functions passed as values"}),"\n",(0,r.jsxs)(t.li,{children:["val patterns like ",(0,r.jsx)(t.code,{children:"val (a, b) = ..."})]}),"\n",(0,r.jsxs)(t.li,{children:["named arguments in ",(0,r.jsx)(t.code,{children:"apply"})," methods"]}),"\n",(0,r.jsx)(t.li,{children:"repeated parameter types (varargs)"}),"\n"]}),"\n",(0,r.jsx)(t.h3,{id:"empty-source-directories-are-no-longer-created",children:"Empty source directories are no longer created"}),"\n",(0,r.jsxs)(t.p,{children:["Previously, Metals created all source directories like ",(0,r.jsx)(t.code,{children:"src/main/java"})," and\n",(0,r.jsx)(t.code,{children:"src/main/scala-2.12"})," even if they were unused. Now, Metals still creates these\ndirectories but removes them again after the file watcher has started. Big\nthanks to first-time contributor ",(0,r.jsx)(t.a,{href:"https://github.com/mudsam",children:"@mudsam"})," for\nimplementing this fix!"]}),"\n",(0,r.jsx)(t.h3,{id:"multiple-workspace-folders",children:"Multiple workspace folders"}),"\n",(0,r.jsxs)(t.p,{children:["Metals now looks for ",(0,r.jsx)(t.code,{children:".scalafmt.conf"})," in all workspace folders instead of only\nthe workspace root."]}),"\n",(0,r.jsxs)(t.h3,{id:"avoid-metalssbt-compile-errors-on-old-sbt-versions",children:["Avoid ",(0,r.jsx)(t.code,{children:"metals.sbt"})," compile errors on old sbt versions"]}),"\n",(0,r.jsxs)(t.p,{children:["The generated ",(0,r.jsx)(t.code,{children:"metals.sbt"})," file in ",(0,r.jsx)(t.code,{children:"~/.sbt/0.13/plugins/metals.sbt"})," now compiles\non all versions of sbt 0.13.x even if Metals itself still only works with sbt\n0.13.17+."]}),"\n",(0,r.jsx)(t.h2,{id:"visual-studio-code",children:"Visual Studio Code"}),"\n",(0,r.jsx)(t.p,{children:"The Metals extension was installed over 1000 times over the past week!"}),"\n",(0,r.jsx)(t.h3,{id:"java_opts",children:(0,r.jsx)(t.code,{children:"JAVA_OPTS"})}),"\n",(0,r.jsxs)(t.p,{children:["The Metals extension now respects the ",(0,r.jsx)(t.code,{children:"JAVA_OPTS"})," environment variable the same\nit does the ",(0,r.jsx)(t.code,{children:".jvmopts"})," file. For example, set ",(0,r.jsx)(t.code,{children:"JAVA_OPTS"})," to\n",(0,r.jsx)(t.code,{children:"-Dhttps.proxyHost=\u2026 -Dhttps.proxyPort=\u2026"})," to configure HTTP proxies. It's\nrecommended to start VS Code with the ",(0,r.jsx)(t.code,{children:"code"})," binary from the terminal to ensure\nenvironment variables propagate correctly."]}),"\n",(0,r.jsx)(t.h2,{id:"sublime-text",children:"Sublime Text"}),"\n",(0,r.jsxs)(t.p,{children:["There's a WIP pull request\n",(0,r.jsx)(t.a,{href:"https://github.com/tomv564/LSP/pull/501",children:"tom654/LSP#501"})," adding support for\nfuzzy symbol search (",(0,r.jsx)(t.code,{children:"workspace/symbol"}),"). Please upvote with \ud83d\udc4d if you'd like to\nuse this feature!"]}),"\n",(0,r.jsx)(t.h2,{id:"emacs",children:"Emacs"}),"\n",(0,r.jsxs)(t.p,{children:["There is a new Gitter channel\n",(0,r.jsx)(t.a,{href:"https://gitter.im/rossabaker/lsp-scala",children:"rossabaker/lsp-scala"})," for Emacs and\nMetals users."]}),"\n",(0,r.jsxs)(t.p,{children:["The ",(0,r.jsx)(t.code,{children:"lsp-scala"})," package is now published to MELPA\n",(0,r.jsx)(t.a,{href:"https://github.com/melpa/melpa/pull/5868",children:"melpa/melpa#5868"}),"."]}),"\n",(0,r.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsxs)(t.li,{children:["Upgrade to Bloop v1.2.5 ",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/513",children:"#513"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Respect workspace folders for Scalafmt formatting, fixes #509.\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/512",children:"#512"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Fix navigation bug for var setters.\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/511",children:"#511"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Upgrade to Scalameta v4.1.3.\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/510",children:"#510"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Change behavior of FileWatcher so that it doesn't create non-existing source\ndirectories ",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/506",children:"#506"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/mudsam",children:"mudsam"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Reference pluginCrossBuild via reflection to support older sbt 0.13.\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/505",children:"#505"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n"]})]})}function h(e={}){const{wrapper:t}={...(0,l.R)(),...e.components};return t?(0,r.jsx)(t,{...e,children:(0,r.jsx)(d,{...e})}):d(e)}},1020:(e,t,s)=>{var r=s(6540),l=Symbol.for("react.element"),n=Symbol.for("react.fragment"),a=Object.prototype.hasOwnProperty,o=r.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,i={key:!0,ref:!0,__self:!0,__source:!0};function c(e,t,s){var r,n={},c=null,d=null;for(r in void 0!==s&&(c=""+s),void 0!==t.key&&(c=""+t.key),void 0!==t.ref&&(d=t.ref),t)a.call(t,r)&&!i.hasOwnProperty(r)&&(n[r]=t[r]);if(e&&e.defaultProps)for(r in t=e.defaultProps)void 0===n[r]&&(n[r]=t[r]);return{$$typeof:l,type:e,key:c,ref:d,props:n,_owner:o.current}}t.Fragment=n,t.jsx=c,t.jsxs=c},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>a,x:()=>o});var r=s(6540);const l={},n=r.createContext(l);function a(e){const t=r.useContext(n);return r.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function o(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:a(e.components),r.createElement(n.Provider,{value:t},e.children)}}}]);