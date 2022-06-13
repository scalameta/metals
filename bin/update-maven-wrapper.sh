#!/usr/bin/env sh
# NOTE: Run this from the root of metals ./bin/update-maven-wrapper.sh

if ! command -v cs *> /dev/null
then
  echo "You must have cs installed to use this script"
  exit 1
fi

latest_wrapper=$(cs complete-dep org.apache.maven.wrapper:maven-wrapper: | tail -1)
latest_maven=$(cs complete-dep org.apache.maven:apache-maven: | tail -1)

curl https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/${latest_wrapper}/maven-wrapper-${latest_wrapper}.jar > $PWD/metals/src/main/resources/maven-wrapper.jar

sed -i "s/.*apache-maven.*/distributionUrl=https:\/\/repo.maven.apache.org\/maven2\/org\/apache\/maven\/apache-maven\/${latest_maven}\/apache-maven-${latest_maven}-bin.zip/" \
  $PWD/metals/src/main/resources/maven-wrapper.properties

sed -i "s/.*maven-wrapper.*/wrapperUrl=https:\/\/repo.maven.apache.org\/maven2\/org\/apache\/maven\/wrapper\/maven-wrapper\/${latest_wrapper}\/maven-wrapper-${latest_wrapper}.jar/" \
  $PWD/metals/src/main/resources/maven-wrapper.properties
