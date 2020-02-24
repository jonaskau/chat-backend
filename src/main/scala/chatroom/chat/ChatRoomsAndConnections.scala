package chatroom.chat

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import chatroom.database.Chat

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


object ChatRoomsAndConnections {
  import chatroom.database.ChatsActor._
  import ChatRoomActor._

  implicit val defaultTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  case class ChatRoom(var usernameList: List[String], chatRoomActor: ActorRef)

  private var ChatRoomMap: Map[String, ChatRoom] = Map.empty[String, ChatRoom]
  private var ConnectionMap: Map[String, Connection] = Map.empty[String, Connection]

  def GetChatRoomsFromDB(chatsActor: ActorRef)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): Unit = {
    val chatListFuture = (chatsActor ? GetAllChats).mapTo[List[Chat]]
    chatListFuture.onComplete{
      case Success(chatList) =>
        ChatRoomMap = chatList
          .map(chat => chat._id.toHexString -> ChatRoom(chat.users, actorSystem.actorOf(Props(new ChatRoomActor(chat._id.toHexString, chat.name, chat.users)), chat._id.toHexString))).toMap
        printChatRoomList()
      case Failure(exception) => println(exception)
    }
  }

  def createOrGetConnection(username: String)(implicit actorSystem: ActorSystem): Connection = {
    if (ConnectionMap.contains(username)) {
      return ConnectionMap(username)
    }
    val connection = Connection(username)
    ChatRoomMap.foreach(chatRoom => {
      if (chatRoom._2.usernameList.contains(username)) {
        connection.addChatRoomActor(chatRoom._1, chatRoom._2.chatRoomActor)
      }
    })
    ConnectionMap += username -> connection
    connection
  }

  def chatRoomContainsUsername(chatId: String, username: String): Boolean = {
    ChatRoomMap(chatId).usernameList.contains(username)
  }

  def removeConnection(username: String): Unit = {
    ConnectionMap -= username
  }

  def insertChatRoom(chat: Chat)(implicit actorSystem: ActorSystem): Unit = {
    val chatRoomActor = actorSystem.actorOf(Props(new ChatRoomActor(chat._id.toHexString, chat.name, chat.users)), chat._id.toHexString)
    ChatRoomMap += chat._id.toHexString -> ChatRoom(chat.users, chatRoomActor)
    addChatRoomActorToConnections(chat.users, chat._id.toHexString, chatRoomActor)
    printChatRoomList()
  }

  def addUsersToChatRoom(chatId: String, users: List[String]): List[String] = {
    val newUsers = users.diff(ChatRoomMap(chatId).usernameList)
    ChatRoomMap(chatId).usernameList ++= newUsers
    ChatRoomMap(chatId).chatRoomActor ! UsersAdded(newUsers)
    addChatRoomActorToConnections(newUsers, chatId, ChatRoomMap(chatId).chatRoomActor)
    printChatRoomList()
    newUsers
  }

  def addChatRoomActorToConnections(newUsers: List[String],
                                    chatId: String,
                                    chatRoomActor: ActorRef): Unit = {
    newUsers.foreach(username => {
      if (ConnectionMap.contains(username)) {
        ConnectionMap(username).addChatRoomActorAndSendUserOnline(chatId, chatRoomActor)
      }
    })
  }

  def printChatRoomList(): Unit = {
    val size = ChatRoomMap.size
    println(s"ChatRoomList: $size entries")
    ChatRoomMap.foreach(chatRoom => println(chatRoom._1, chatRoom._2.usernameList, chatRoom._2.chatRoomActor.path))
  }

  def printConnectionMap(): Unit = {
    val size = ConnectionMap.size
    println(s"ConnectionMap: $size entries")
    ConnectionMap.foreach(connection => println(connection._1))
  }

  /*def GetOnlineUsers(chatId: String): Future[List[String]] = {
    (ChatRoomMap(chatId).chatRoomActor ? GetOnlineUser).mapTo[List[String]]
  }*/

  /*def GetChatNameById(chatId: String): String = {
    ChatRoomMap(chatId).name
  }*/

  /*def GetChatNameByIdIfUsernameIsInside(chatId: String, username: String): String = {
    if (chatRoomContainsUsername(chatId, username))
      ChatRoomMap(chatId).name
    else
      null
  }*/

  /* GetChatByIdIfUsernameIsInside(chatId: String, username: String): (String, List[String]) = {
    if (chatRoomContainsUsername(chatId, username))
      (ChatRoomMap(chatId).name, ChatRoomMap(chatId).usernameList)
    else
      null
  }*/
}