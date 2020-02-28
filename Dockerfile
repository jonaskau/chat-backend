FROM openjdk:8

RUN \
  curl -L -o sbt-1.3.5.deb http://dl.bintray.com/sbt/debian/sbt-1.3.5.deb && \
  dpkg -i sbt-1.3.5.deb && \
  rm sbt-1.3.5.deb && \
  apt-get update && \
  apt-get install sbt && \
  sbt sbtVersion

WORKDIR /

ADD . .

EXPOSE 8080

CMD sbt run