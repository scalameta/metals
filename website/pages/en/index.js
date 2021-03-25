/**
 * Copyright (c) 2017-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

const React = require("react");

const CompLibrary = require("../../core/CompLibrary.js");
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

const siteConfig = require(process.cwd() + "/siteConfig.js");

function docUrl(doc, language) {
  return siteConfig.baseUrl + "docs/" + (language ? language + "/" : "") + doc;
}

class Button extends React.Component {
  render() {
    return (
      <div className="pluginWrapper buttonWrapper">
        <a className="button" href={this.props.href} target={this.props.target}>
          {this.props.children}
        </a>
      </div>
    );
  }
}

class Dropdown extends React.Component {
  render() {
    const gitpod = (template, organization) =>
      `https://gitpod.io/#template=${template},organization=${organization}/https://github.com/scalameta/gitpod-g8`;

    const links = [
      { organization: "scala", template: "hello-world", label: "Hello World!" },
      { organization: "scala", template: "scala3", label: "Scala 3" },
      {
        organization: "scala",
        template: "scalatest-example",
        label: "Scalatest",
      },
      {
        organization: "akka",
        template: "akka-scala-seed",
        label: "Akka",
      },
      {
        organization: "zio",
        template: "zio-project-seed",
        label: "ZIO",
      },
      {
        organization: "playframework",
        template: "play-scala-seed",
        label: "Play Framework",
      },
      {
        organization: "scala-native",
        template: "scala-native",
        label: "Scala Native",
      },
    ].map(({ organization, template, label }) => (
      <a
        target="_blank"
        rel="noopener noreferrer"
        href={gitpod(template, organization)}
      >
        {label}
      </a>
    ));

    if (this.props.show)
      return (
        <div className="dropdown">
          <Button>TRY ONLINE WITH GITPOD</Button>
          <div className="dropdown-content">{links}</div>
        </div>
      );
    else return <div />;
  }
}

Button.defaultProps = {
  target: "_self",
};

const SplashContainer = (props) => (
  <div className="homeContainer">
    <div className="homeSplashFade">
      <div className="wrapper homeWrapper">{props.children}</div>
    </div>
  </div>
);

const ProjectTitle = (props) => (
  <h2 className="projectTitle">
    {siteConfig.title}
    <small>{siteConfig.tagline}</small>
  </h2>
);

const PromoSection = (props) => (
  <div className="section promoSection">
    <div className="promoRow">
      <div className="pluginRowBlock">{props.children}</div>
    </div>
  </div>
);

class HomeSplash extends React.Component {
  render() {
    let language = this.props.language || "";
    return (
      <SplashContainer>
        <div className="inner">
          <ProjectTitle />
          <PromoSection>
            <Button href={docUrl("editors/overview.html", language)}>
              Get Started
            </Button>
            <Dropdown show="true" />
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

const Block = (props) => (
  <Container
    padding={["bottom", "top"]}
    id={props.id}
    background={props.background}
  >
    <GridBlock align="left" contents={props.children} layout={props.layout} />
    <Dropdown show={props.showDropdown} />
  </Container>
);

const Features = (props) => {
  const features = [
    {
      title: "Simple installation",
      content: "Open a directory, import your build and start coding.",
      image: "https://i.imgur.com/L5CurFG.png",
      imageAlign: "left",
    },
    {
      title: "Accurate diagnostics",
      content:
        "Compile on file save and see errors from the build tool directly inside the editor. No more switching focus to the console.",
      image: "https://i.imgur.com/JYLQGrc.gif",
      imageAlign: "right",
    },
    {
      title: "Rich build tool support",
      content:
        `The build tools sbt, Gradle, Maven and Mill are supported thanks to <a href="https://scalacenter.github.io/bloop/">Bloop</a>. ` +
        `Hot incremental compilation in the Bloop build server ensures compile errors appear as quickly as possible.`,
      image:
        "https://user-images.githubusercontent.com/1408093/68486864-dd9f2b00-01f6-11ea-9291-d3a7ce6ef225.png",
      imageAlign: "left",
    },
    {
      title: "Goto definition",
      content:
        "Jump to symbol definitions in your project sources and Scala/Java library dependencies.",
      image: "https://i.imgur.com/bCIhFof.gif",
      imageAlign: "right",
    },
    {
      title: "Completions",
      content:
        "Explore new library APIs, implement interfaces, generate exhaustive matches and more.",
      image:
        "https://user-images.githubusercontent.com/1408093/56036958-725bac00-5d2e-11e9-9cf7-46249125494a.gif",
      imageAlign: "left",
    },
    {
      title: "Hover (aka. type at point)",
      content: "See the expression type and symbol signature under the cursor.",
      image: "https://i.imgur.com/2MfQvsM.gif",
      imageAlign: "right",
    },
    {
      title: "Signature help (aka. parameter hints)",
      content:
        "View a method signature and method overloads as you fill in the arguments.",
      image: "https://i.imgur.com/DAWIrHu.gif",
      imageAlign: "left",
    },
    {
      title: "Find symbol references",
      content: "Find all usages of a symbol in the workspace.",
      image:
        "https://user-images.githubusercontent.com/1408093/51089190-75fc8880-1769-11e9-819c-95262205e95c.png",
      imageAlign: "right",
    },
    {
      title: "Fuzzy symbol search",
      content: "Search for symbols in the workspace or library dependencies.",
      image: "https://i.imgur.com/w5yrK1w.gif",
      imageAlign: "left",
    },
    {
      title: "Try it out in an online IDE",
      content: `With Gitpod online IDE, you can try out Metals with just one click. 
         In the Gitpod Examples dropdown above, select Scala repository template that you want to set up.`,
      image: "https://imgur.com/2AiIN43.gif",
      imageAlign: "right",
      showDropdown: "true",
    },
  ];
  return (
    <div
      className="productShowcaseSection paddingBottom"
      style={{ textAlign: "left" }}
    >
      {features.map((feature) => (
        <div>
          <Block key={feature.title} showDropdown={feature.showDropdown}>
            {[feature]}
          </Block>
        </div>
      ))}
    </div>
  );
};
class Index extends React.Component {
  render() {
    let language = this.props.language || "";

    return (
      <div>
        <HomeSplash language={language} />
        <Features />
      </div>
    );
  }
}

module.exports = Index;
