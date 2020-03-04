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
  case class UsersAdded(newUsers: List[String]) extends ChatEvent
  case class IncomingMessage(sender: String, message: String) extends ChatEvent

  sealed trait Outgoing
  case class OutgoingMessage(chatId: String, author: String, message: String) extends Outgoing
  case class OutgoingEvent(chatId: String,
                           username: String,
                           userOnline: Boolean,
                           userOffline: Boolean,
                           userAdded: Boolean) extends Outgoing
  case class OutgoingChat(chatId: String, chatName: String, users: List[String], userOnlineList: List[String]) extends Outgoing
  case class OutgoingChatAmount(amount: Int) extends Outgoing
}
class ChatRoomActor(chatId: String, chatName: String, var userList: List[String]) extends Actor {
  import ChatRoomActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val defaultTimeout: Timeout = Timeout(30, TimeUnit.SECONDS)

  var userOnlineMap: Map[(String, Int), ActorRef] = Map.empty[(String, Int), ActorRef]

  override def receive: Receive = {
    case UserOnline(username, accountConnectionNumber, userActor) =>
      val containsUsernameBefore = containsUsername(username)
      userOnlineMap += (username, accountConnectionNumber) -> userActor
      userActor ! OutgoingChat(chatId, chatName, userList, userOnlineMap.keys.map[String](_._1).toList.distinct)
      if (!containsUsernameBefore) {
        broadcastEvent(username, userOnline = true) // I like this default value syntax
      }
    case UserOffline(username, accountConnectionNumber: Int) =>
      userOnlineMap -= ((username, accountConnectionNumber))
      if (!containsUsername(username)) {
        broadcastEvent(username, userOffline = true)
      }
    case UsersAdded(newUsers) =>
      userList ++= newUsers
      newUsers.foreach(username => broadcastEvent(username, userAdded = true))
    case IncomingMessage(sender, message) =>
      saveAndBroadcastMessage(sender, message)
  }

  def saveAndBroadcastMessage(sender: String, message: String): Unit = {
    val insertedFuture = (DatabaseService.messagesActor ? InsertMessage(new ObjectId(chatId), sender, message)).mapTo[Boolean]
    insertedFuture.onComplete {
      case Success(inserted) =>
        if (inserted)
          broadcast(OutgoingMessage(chatId, sender, message))
        else
          println("couldnt save message to DB")
      case Failure(_) => println("couldnt save message to DB")
    }
  }

  def broadcastEvent(username: String,
                     userOnline: Boolean = false,
                     userOffline: Boolean = false,
                     userAdded: Boolean = false): Unit = {
    broadcast(OutgoingEvent(chatId, username, userOnline, userOffline, userAdded))
  }

  def broadcast(outgoing: Outgoing): Unit = {
    userOnlineMap.values.foreach(_ ! outgoing)
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
