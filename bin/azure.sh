set -eux
curl -sLO https://github.com/shyiko/jabba/raw/master/install.sh
chmod +x install.sh
./install.sh
source ~/.jabba/jabba.sh
jabba install adopt@1.8.0-222
jabba use adopt@1.8.0-222
java -version

function bloop_version {
  cat build.sbt | grep "val bloop" | sed 's|[^0-9.]||g'
}

wget -O bin/coursier https://git.io/coursier-cli && chmod +x bin/coursier \
&& bin/coursier launch ch.epfl.scala:bloopgun-core_2.12:$(bloop_version) -- about

./sbt unit/test
