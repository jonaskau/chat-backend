package chatroom.chat

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import chatroom.database.DatabaseService
import chatroom.database.MessagesActor.InsertMessage
import org.mongodb.scala.bson.ObjectId

import scala.util.{Failure, Success}

object ChatRoomActor {
  sealed trait ChatEvent
  case class UserOnline(username: String, userActor: ActorRef) extends ChatEvent
  case class UserOffline(username: String) extends ChatEvent
  case class IncomingMessage(sender: String, message: String) extends ChatEvent
  case class OutgoingMessage(chatId: String, author: String, message: String)
}
class ChatRoomActor(chatId: String) extends Actor {
  import ChatRoomActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val defaultTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  var userOnlineMap: Map[String, ActorRef] = Map.empty[String, ActorRef]

  override def receive: Receive = {
    case UserOnline(username, userActor) =>
      userOnlineMap += username -> userActor
      broadcast("System", s"$username: online")
    case UserOffline(username) =>
      broadcast("System", s"$username: offline")
      userOnlineMap -= username
    case IncomingMessage(sender, message) =>
      val insertedFuture = (DatabaseService.messagesActor ? InsertMessage(new ObjectId(chatId), sender, message)).mapTo[Boolean]
      insertedFuture.onComplete {
        case Success(inserted) =>
          if (inserted) broadcast(sender, message)
          else println("couldnt save message to DB")
        case Failure(_) => println("couldnt save message to DB")
      }
  }

  def broadcast(sender: String, message: String): Unit =
    userOnlineMap.values.foreach(_ ! OutgoingMessage(chatId, sender, message))
}
