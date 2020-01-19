package chatroom.services

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout

object ImplicitsService {
  implicit val actorSystem: ActorSystem = ActorSystem("actorSystem")
  implicit val flowMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val defaultTimeout: Timeout = Timeout(2, TimeUnit.SECONDS)
}
