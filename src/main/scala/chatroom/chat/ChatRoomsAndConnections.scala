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

  implicit val defaultTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  case class ChatRoom(var usernameList: List[String], chatRoomActor: ActorRef)

  private var ChatRoomMap: Map[String, ChatRoom] = Map.empty[String, ChatRoom]
  private var ConnectionMap: Map[String, Connection] = Map.empty[String, Connection]

  def GetChatRoomsFromDB(chatsActor: ActorRef)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): Unit = {
    val chatListFuture = (chatsActor ? GetAllChats).mapTo[List[Chat]]
    chatListFuture.onComplete{
      case Success(chatList) =>
        ChatRoomMap = chatList
          .map(chat => chat._id.toHexString -> ChatRoom(chat.users, actorSystem.actorOf(Props(new ChatRoomActor(chat._id.toHexString)), chat._id.toHexString))).toMap
        printChatRoomList()
      case Failure(exception) => println(exception)
    }
  }

  def createConnection(username: String)(implicit actorSystem: ActorSystem): Connection = {
    val connection = Connection(username)
    ChatRoomMap.foreach(chatRoom => {
      if (chatRoom._2.usernameList.contains(username)) {
        connection.addChatRoomActor(chatRoom._1, chatRoom._2.chatRoomActor)
      }
    })
    ConnectionMap += username -> connection
    connection
  }

  def removeConnection(username: String): Unit = {
    ChatRoomMap -= username
  }

  def insertChatRoom(chat: Chat)(implicit actorSystem: ActorSystem): Unit = {
    val chatRoomActor = actorSystem.actorOf(Props(new ChatRoomActor(chat._id.toHexString)), chat._id.toHexString)
    ChatRoomMap += chat._id.toHexString -> ChatRoom(chat.users, chatRoomActor)
    addChatRoomActorsToConnections(chat.users, chat._id.toHexString, chatRoomActor)
    printChatRoomList()
  }

  def addUsersToChatRoom(chatId: String, users: List[String]): Unit = {
    val newUsers = users.diff(ChatRoomMap(chatId).usernameList)
    ChatRoomMap(chatId).usernameList ++= newUsers
    addChatRoomActorsToConnections(newUsers, chatId, ChatRoomMap(chatId).chatRoomActor)
    printChatRoomList()
  }

  def addChatRoomActorsToConnections(newUsers: List[String], chatId: String, chatRoomActor: ActorRef): Unit = {
    newUsers.foreach(username => {
      if (ConnectionMap.contains(username)) {
        ConnectionMap(username).addChatRoomActorAndSendUserOnline(chatId, chatRoomActor)
      }
    })
  }

  def printChatRoomList(): Unit = {
    val size = ChatRoomMap.size
    println(s"ChatRoomList: $size entries")
    ChatRoomMap.foreach(chatroom => println(chatroom._1, chatroom._2.usernameList, chatroom._2.chatRoomActor.path))
  }
}