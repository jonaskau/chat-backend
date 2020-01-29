name := "Chat"

version := "0.1"

scalaVersion := "2.13.1"

val akkaVersion = "2.5.27"
val akkaHttpVersion = "10.1.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  // JWT
  "com.pauldijou" %% "jwt-spray-json" % "4.2.0",
  //MongoDB
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.8.0",
  //Cors
  "ch.megard" %% "akka-http-cors" % "0.4.2"
)