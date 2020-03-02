FROM mozilla/sbt:latest

WORKDIR /usr/app

ADD . .

RUN sbt compile

CMD sbt run

EXPOSE 80
