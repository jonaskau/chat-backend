package chatroom.chat

import akka.actor.{Actor, ActorRef, PoisonPill}

object ConnectionActor {
  sealed trait ConnectionEvent
  case class AddChatRoomActor(chatId: String, chatRoomActor: ActorRef) extends ConnectionEvent
  case class AddChatRoomActorAndSendUserOnline(username: String,
                                               chatId: String,
                                               chatName: String,
                                               users: List[String],
                                               chatRoomActor: ActorRef) extends ConnectionEvent
  case class SendUserOnline(username: String, accountConnectionNumber: Int, userActor: ActorRef) extends ConnectionEvent
  case class SendUserOffline(username: String, accountConnectionNumber: Int) extends ConnectionEvent
  case class SendIncomingMessage(chatId: String, author: String, message: String) extends ConnectionEvent
}
class ConnectionActor extends Actor{
  import ChatRoomActor._
  import ConnectionActor._

  var chatRoomActors: Map[String, ActorRef] = Map.empty[String, ActorRef]
  var userActors: Map[Int, ActorRef] = Map.empty[Int, ActorRef]

  override def receive: Receive = {
    case AddChatRoomActor(chatId, chatRoomActor) =>
      chatRoomActors += chatId -> chatRoomActor
    case AddChatRoomActorAndSendUserOnline(username, chatId, chatName, users, chatRoomActor) =>
      chatRoomActors += chatId -> chatRoomActor
      userActors.foreach(userActor => {
        userActor._2 ! OutgoingChatNameAndUserList(chatId, chatName, users)
        chatRoomActors(chatId) ! UserOnline(username, userActor._1, userActor._2)
      })
    case SendUserOnline(username, accountConnectionNumber, userActor) =>
      userActors += accountConnectionNumber -> userActor
      chatRoomActors.values.foreach(_ ! UserOnline(username, accountConnectionNumber, userActor))
    case SendUserOffline(username, accountConnectionNumber) =>
      userActors -= accountConnectionNumber
      chatRoomActors.values.foreach(_ ! UserOffline(username, accountConnectionNumber))
      if (userActors.isEmpty) {
        ChatRoomsAndConnections.removeConnection(username)
        self ! PoisonPill
      }
    case SendIncomingMessage(chatId, sender, message) =>
      chatRoomActors(chatId) ! IncomingMessage(sender, message)
  }
}
