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

function imgUrl(img) {
  return siteConfig.baseUrl + "img/" + img;
}

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

Button.defaultProps = {
  target: "_self"
};

const SplashContainer = props => (
  <div className="homeContainer">
    <div className="homeSplashFade">
      <div className="wrapper homeWrapper">{props.children}</div>
    </div>
  </div>
);

const ProjectTitle = props => (
  <h2 className="projectTitle">
    {siteConfig.title}
    <small>{siteConfig.tagline}</small>
  </h2>
);

const PromoSection = props => (
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
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

const Block = props => (
  <Container
    padding={["bottom", "top"]}
    id={props.id}
    background={props.background}
  >
    <GridBlock align="left" contents={props.children} layout={props.layout} />
  </Container>
);

const Features = props => {
  const features = [
    {
      title: "Simple installation",
      content: "Open a directory, import your build and start coding.",
      image: imgUrl("simple-installation.png"),
      imageAlign: "left"
    },
    {
      title: "Accurate diagnostics",
      content:
        "Compile on file save and see errors from the build tool, no more spurious red squiggles or switching focus to the console.",
      image: imgUrl("accurate-diagnostics.png"),
      imageAlign: "right"
    },
    {
      title: "Goto definition",
      content:
        "Jump to symbol definitions in your project sources and Scala/Java library dependencies.",
      image: imgUrl("goto-definition.gif"),
      imageAlign: "left"
    }
  ];
  return (
    <div
      className="productShowcaseSection paddingBottom"
      style={{ textAlign: "left" }}
    >
      {features.map(feature => (
        <Block key={feature.title}>{[feature]}</Block>
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
