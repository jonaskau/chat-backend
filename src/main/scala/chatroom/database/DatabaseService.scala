package chatroom.database

import akka.actor.{ActorRef, Props}
import chatroom.services.ImplicitsService
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.{MongoClient, MongoDatabase}

object DatabaseService {

  private val codecRegistry = fromRegistries(
    fromProviders(classOf[Chat]),
    fromProviders(classOf[User]),
    fromProviders(classOf[Message]),
    DEFAULT_CODEC_REGISTRY)
  private val uri: String = "mongodb+srv://chatroom:chatroom@chatroom-7wo0b.mongodb.net/test?retryWrites=true&w=majority"
  val client: MongoClient = MongoClient(uri)
  val chatroomDB: MongoDatabase = client.getDatabase("chatroom").withCodecRegistry(codecRegistry) //retry

  import ImplicitsService._
  val usersActor: ActorRef = actorSystem.actorOf(Props[UsersActor], "usersActor")
  val chatsActor: ActorRef = actorSystem.actorOf(Props[ChatsActor], "chatsActor")
  val messagesActor: ActorRef = actorSystem.actorOf(Props[MessagesActor], "messagesActor")
}
