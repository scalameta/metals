"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["2118"],{44811:function(e,t,i){i.r(t),i.d(t,{metadata:()=>n,contentTitle:()=>o,default:()=>h,assets:()=>d,toc:()=>a,frontMatter:()=>r});var n=JSON.parse('{"id":"build-tools/overview","title":"Build Tools Overview","description":"Metals works with the following build tools with varying degrees of","source":"@site/target/docs/build-tools/overview.md","sourceDirName":"build-tools","slug":"/build-tools/overview","permalink":"/metals/docs/build-tools/overview","draft":false,"unlisted":false,"editUrl":"https://github.com/scalameta/metals/edit/main/docs/build-tools/overview.md","tags":[],"version":"current","frontMatter":{"id":"overview","title":"Build Tools Overview","sidebar_label":"Overview"},"sidebar":"docs","previous":{"title":"Scripts support","permalink":"/metals/docs/features/scripts"},"next":{"title":"Bazel","permalink":"/metals/docs/build-tools/bazel"}}'),l=i("85893"),s=i("50065");let r={id:"overview",title:"Build Tools Overview",sidebar_label:"Overview"},o=void 0,d={},a=[{value:"Installation",id:"installation",level:2},{value:"Goto library dependencies",id:"goto-library-dependencies",level:2},{value:"Find references",id:"find-references",level:2},{value:"Integrating a new build tool",id:"integrating-a-new-build-tool",level:2}];function c(e){let t={a:"a",code:"code",h2:"h2",p:"p",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",...(0,s.a)(),...e.components};return(0,l.jsxs)(l.Fragment,{children:[(0,l.jsx)(t.p,{children:"Metals works with the following build tools with varying degrees of\nfunctionality."}),"\n",(0,l.jsxs)(t.table,{children:[(0,l.jsx)(t.thead,{children:(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.th,{children:"Build tool"}),(0,l.jsx)(t.th,{style:{textAlign:"center"},children:"Installation"}),(0,l.jsx)(t.th,{style:{textAlign:"center"},children:"Goto library dependencies"}),(0,l.jsx)(t.th,{style:{textAlign:"center"},children:"Find references"})]})}),(0,l.jsxs)(t.tbody,{children:[(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/sbt",children:"sbt"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic via Bloop"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"})]}),(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/maven",children:"Maven"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic via Bloop"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Semi-automatic"})]}),(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/gradle",children:"Gradle"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic via Bloop"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"})]}),(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/mill",children:"Mill"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic via Bloop"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"})]}),(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/bloop",children:"Bloop"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Semi-automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Semi-automatic"})]}),(0,l.jsxs)(t.tr,{children:[(0,l.jsx)(t.td,{children:(0,l.jsx)(t.a,{href:"/metals/docs/build-tools/bazel",children:"Bazel"})}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Automatic"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Build definition"}),(0,l.jsx)(t.td,{style:{textAlign:"center"},children:"Semi-automatic"})]})]})]}),"\n",(0,l.jsx)(t.h2,{id:"installation",children:"Installation"}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Automatic via Bloop"}),": you can import the build directly from the language\nserver without the need for running custom steps in the terminal. The build is\nexported to ",(0,l.jsx)(t.a,{href:"https://scalacenter.github.io/bloop/",children:"Bloop"}),", a Scala build server\nthat provides fast incremental compilation."]}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Automatic"}),": you can import the build directly from the language server\nwithout the need for running custom steps in the terminal. To use automatic\ninstallation start the Metals language server in the root directory of your\nbuild."]}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Manual"}),": setting up Metals requires a few manual steps to generate\n",(0,l.jsx)(t.a,{href:"https://scalacenter.github.io/bloop",children:"Bloop"})," JSON files. In addition to normal\nBloop installation, Metals requires that the project sources are compiled with\nthe\n",(0,l.jsx)(t.a,{href:"https://scalameta.org/docs/semanticdb/guide.html#producing-semanticdb",children:"semanticdb-scalac"}),"\ncompiler plugin and ",(0,l.jsx)(t.code,{children:"-Yrangepos"})," option enabled."]}),"\n",(0,l.jsx)(t.h2,{id:"goto-library-dependencies",children:"Goto library dependencies"}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Automatic"}),': it is possible to navigate Scala+Java library dependencies using\n"Goto definition".']}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Semi-automatic"}),": navigation in library dependency sources works as long as\nthe\n",(0,l.jsx)(t.a,{href:"https://scalacenter.github.io/bloop/docs/configuration-format/",children:"Bloop JSON files"}),"\nare populated with ",(0,l.jsx)(t.code,{children:"*-sources.jar"}),"."]}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Build definition"}),": navigation in library dependency sources works as long as\nthe sources are enabled for library dependencies in the Bazel definition."]}),"\n",(0,l.jsx)(t.h2,{id:"find-references",children:"Find references"}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Automatic"}),": it is possible to find all references to a symbol in the project."]}),"\n",(0,l.jsxs)(t.p,{children:[(0,l.jsx)(t.strong,{children:"Semi-automatic"}),": it is possible to 'Find symbol references' as soon the\nSemanticDB compiler plugin is manually enabled in the build, check separate\nbuild tool pages for details."]}),"\n",(0,l.jsx)(t.h2,{id:"integrating-a-new-build-tool",children:"Integrating a new build tool"}),"\n",(0,l.jsxs)(t.p,{children:["Metals works with any build tool that supports the\n",(0,l.jsx)(t.a,{href:"https://github.com/scalacenter/bsp/blob/master/docs/bsp.md",children:"Build Server Protocol"}),".\nFor more information, see the\n",(0,l.jsx)(t.a,{href:"/metals/docs/integrations/new-build-tool",children:"guide to integrate new build tools"}),"."]})]})}function h(e={}){let{wrapper:t}={...(0,s.a)(),...e.components};return t?(0,l.jsx)(t,{...e,children:(0,l.jsx)(c,{...e})}):c(e)}},50065:function(e,t,i){i.d(t,{Z:function(){return o},a:function(){return r}});var n=i(67294);let l={},s=n.createContext(l);function r(e){let t=n.useContext(s);return n.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function o(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:r(e.components),n.createElement(s.Provider,{value:t},e.children)}}}]);