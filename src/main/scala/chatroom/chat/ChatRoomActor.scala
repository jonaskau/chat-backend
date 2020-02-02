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
  case class UserOnline(username: String, accountConnectionNumber: Int, userActor: ActorRef) extends ChatEvent
  case class UserOffline(username: String, accountConnectionNumber: Int) extends ChatEvent
  case class IncomingMessage(sender: String, message: String) extends ChatEvent
  case class OutgoingMessage(chatId: String, author: String, message: String)
}
class ChatRoomActor(chatId: String) extends Actor {
  import ChatRoomActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val defaultTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  var userOnlineMap: Map[(String, Int), ActorRef] = Map.empty[(String, Int), ActorRef]

  override def receive: Receive = {
    case UserOnline(username, accountConnectionNumber, userActor) =>
      val containsUsernameBefore = containsUsername(username)
      userOnlineMap += (username, accountConnectionNumber) -> userActor
      if (!containsUsernameBefore) {
        broadcast("System", s"$username: online")
      }
    case UserOffline(username, accountConnectionNumber: Int) =>
      userOnlineMap -= ((username, accountConnectionNumber))
      if (!containsUsername(username)) {
        broadcast("System", s"$username: offline")
      }
    case IncomingMessage(sender, message) =>
      val insertedFuture = (DatabaseService.messagesActor ? InsertMessage(new ObjectId(chatId), sender, message)).mapTo[Boolean]
      insertedFuture.onComplete {
        case Success(inserted) =>
          if (inserted) broadcast(sender, message)
          else println("couldnt save message to DB")
        case Failure(_) => println("couldnt save message to DB")
      }
  }

  def broadcast(sender: String, message: String): Unit = {
    userOnlineMap.values.foreach(_ ! OutgoingMessage(chatId, sender, message))
  }

  def containsUsername(username: String): Boolean = {
    var contains = false
    userOnlineMap.keys.foreach(key => {
      if (key._1 == username) {
        contains = true
      }
    })
    contains
  }
}
