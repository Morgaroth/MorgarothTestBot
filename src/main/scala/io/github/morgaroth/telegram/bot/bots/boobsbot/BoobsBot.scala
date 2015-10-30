package io.github.morgaroth.telegram.bot.bots.boobsbot

import java.io.{File => JFile}
import java.security.MessageDigest
import javax.xml.bind.DatatypeConverter

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingAdapter
import com.mongodb.MongoException.DuplicateKey
import com.mongodb.casbah.commons.MongoDBObject
import com.typesafe.config.Config
import io.github.morgaroth.telegram.bot.bots.boobsbot.BoobsBot.PublishBoobs
import io.github.morgaroth.telegram.bot.bots.boobsbot.FetchAndCalculateHash.{NoContentInformation, UnsupportedBoobsContent}
import io.github.morgaroth.telegram.bot.core.api.methods.Response
import io.github.morgaroth.telegram.bot.core.api.models._
import io.github.morgaroth.telegram.bot.core.api.models.extractors.{MultiArgCommand, NoArgCommand, SingleArgCommand}
import io.github.morgaroth.telegram.bot.core.api.models.formats._
import io.github.morgaroth.telegram.bot.core.engine.NewUpdate
import io.github.morgaroth.telegram.bot.core.engine.core.BotActor._
import org.bson.types.ObjectId

import scala.language.reflectiveCalls
import scala.util.{Failure, Success, Try}


object BoobsBot {

  private[BoobsBot] case class PublishBoobs(content: Boobs, owner: Chat, worker: Option[ActorRef] = None)

  def props(dbCfg: Config) = Props(classOf[BoobsBot], dbCfg)
}

class BoobsBot(dbCfg: Config) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  implicit def implLog: LoggingAdapter = log

  val hardSelf = self

  val FilesDao = new BoobsDao {
    override def config = dbCfg
  }
  val SubsDao = new BoobsListenerDao {
    override def config = dbCfg
  }
  val WaitingLinks = {
    log.info(s"accessing waiting links $dbCfg")
    new BoobsInMotionGIFDao {
      override def dbConfig: Config = dbCfg
    }
  }

  val questions = scala.collection.mutable.Map.empty[Int, (ObjectId, String)]
  //
  //  @tailrec
  //  private def firstEmpty: String = {
  //    Random.alphanumeric.take(2).mkString match {
  //      case valid if !questions.contains(valid) => valid
  //      case _ => firstEmpty
  //    }
  //  }

  override def receive: Receive = {
    case SingleArgCommand("resolve", arg, (chat, _, _)) =>
      resolveLink(arg, chat, sender())

    case SingleArgCommand("add", arg, (chat, _, _)) =>
      resolveLink(arg, chat, sender(), publish = false)

    case NoArgCommand("stats", (chat, _, _)) =>
      sender() ! SendMessage(chat.chatId,
        s"""Status are:
           |* all files in database = ${FilesDao.dao.count()}
           |* all subscribers in database = ${SubsDao.dao.count()}
           |
          |This is all for now.
      """.stripMargin)

    case NoArgCommand("all", (ch, user, _)) if user.id == 36792931 =>
      FilesDao.dao.find(MongoDBObject.empty).foreach(f =>
        sender() ! SendBoobsCorrectType(ch.chatId, f)
      )

    case MultiArgCommand("grade", args, (ch, user, _)) if user.id == 36792931 =>
      sendBoobsToGrade(ch)

    case NoArgCommand(comm, (ch, user, _)) if comm.startsWith("grade_") =>
      val arg = comm.split("_").toList.tail.head
      log.info(s"received grade $arg command")
      (questions.get(ch.chatId), arg) match {
        case (Some((fileId, telegram_f_id)), "YES") =>
          val a = WaitingLinks.updateStatus(fileId, BoobsInMotionGIF.ACC)
          doSthWithNewFile(ch, sender(), Boobs(telegram_f_id, Boobs.document, a.get.hash, Some(ch.uber)), publish = false)
        case (Some((fileId, telegram_f_id)), "PUBLISH") =>
          val a = WaitingLinks.updateStatus(fileId, BoobsInMotionGIF.ACC)
          doSthWithNewFile(ch, sender(), Boobs(telegram_f_id, Boobs.document, a.get.hash, Some(ch.uber)), publish = true)
        case (Some((fileId, telegram_f_id)), "NO") =>
          WaitingLinks.updateStatus(fileId, BoobsInMotionGIF.REJECTED)
        case (a, b) =>
          sender() ! SendMessage(ch.chatId, s"Sorry, I dont know $b with cache $a, but I have sth for You:")
          sender() ! sendBoobs(1, ch.chatId)
      }
      questions -= ch.chatId
      sendBoobsToGrade(ch)

    case NoArgCommand("stopgrade", (ch, _, _)) =>
      questions -= ch.chatId
      sender() ! SendMessage(ch.chatId, "OK, end", reply_markup = ReplyKeyboardHide())

    case MultiArgCommand("updatedb", args, (ch, user, _)) if user.id == 36792931 =>
      val sen = sender()
      Try {
        val f = args.map(_.toInt) match {
          case from :: to :: Nil => LinksFromTumblrFetch.runAsync("boobsinmotion", from, Some(to))
          case from :: Nil => LinksFromTumblrFetch.runAsync("boobsinmotion", from)
          case Nil => LinksFromTumblrFetch.runAsync("boobsinmotion", 0)
        }
        f.onComplete {
          case Success(inserted) =>
            sen ! SendMessage(ch.chatId, s"fetching BoobsinMotion blog with args $args end with $inserted new links")
          case Failure(t) =>
            sen ! SendMessage(ch.chatId, s"fetching BoobsinMotion blog with args $args end with error: ${t.getMessage}")
            log.error(t, "fetching images")
        }
      }.recover {
        case t =>
          sen ! SendMessage(ch.chatId, s"parsing arguments for command fetching blog end with error ${t.getMessage}")
          log.error(t, "parsing arguments")
      }

    case NoArgCommand("start", (ch, _, _)) =>
      sendHelp(ch.chatId)

    case NoArgCommand("subscribe", (ch, _, _)) =>
      val response = Try {
        SubsDao.dao.insert(BoobsListener(ch.chatId.toString, ch.chatId))
        "This chat is subscribed!"
      }.recover {
        case e: DuplicateKey => "This chat is already subscribed"
        case anot => s"Another error ${anot.getMessage}"
      }.get
      sender() ! SendMessage(ch.chatId, response)

    case NoArgCommand("boobs" | "make_me_happy" | "give_me_boobs", (ch, _, _)) =>
      sendBoobs(1, ch.chatId)

    case NoArgCommand("unsubscribe", (ch, _, _)) =>
      val wr = SubsDao.dao.remove(MongoDBObject("chatId" -> ch.chatId))
      val response = if (wr.getN == 0) "Unsubscribed (newer was)" else "Unsubscribed :("
      sender() ! SendMessage(ch.chatId, response)


    case NewUpdate(id, _, u@Update(_, m)) if m.text.isDefined =>
      val g = m.text.get
      g.stripPrefix("/") match {
        //        case getImages if getImages.startsWith("get") =>
        //          val numberStr = getImages.stripPrefix("get").dropWhile(_ == " ").takeWhile(_ != " ")
        //          log.info(s"parsed number $numberStr")
        //          val count = Math.min(Try(numberStr.trim.toInt).toOption.getOrElse(1), 5)
        //          FilesDao.random(count).foreach(fId =>
        //            sender() ! SendBoobsCorrectType(m.chatId, fId)
        //          )
        case delete if delete.startsWith("delete") =>
          if (m.reply_to_message.isDefined) {
            if (m.reply_to_message.get.document.isDefined) {
              val toRemove = m.reply_to_message.get.document.get.file_id
              FilesDao.dao.remove(MongoDBObject("_id" -> toRemove))
              sender() ! SendMessage(m.chatId, "Deleted.")
            } else if (m.reply_to_message.get.photo.isDefined) {
              val photos = m.reply_to_message.get.photo.get
              val biggest: String = photos.sortBy(_.width).last.file_id
              FilesDao.dao.remove(MongoDBObject("_id" -> biggest))
              sender() ! SendMessage(m.chatId, "Deleted.")
            } else {
              sender() ! SendMessage(m.chatId, "Sorry, this message isn't a photo/file comment of reply.")
            }
          } else {
            sender() ! SendMessage(m.chatId, "Sorry, this message isn't a comment of reply.")
          }
        case help if help.startsWith("help") =>
          sendHelp(m.chatId)
        case illegal if m.reply_to_message.isDefined =>
        // ignore, someone only commented Bot message
        case unknown =>
          sender() ! SendMessage(m.chatId, s"Sorry, I dont know command $unknown, but I have sth for You:")
          sender() ! sendBoobs(1, m.chatId)
      }
      sender() ! Handled(id)

    // wyłapywanie obrazków
    case NewUpdate(id, _, u@Update(_, m)) if m.photo.isDefined =>
      // catched photo
      val photos = m.photo.get
      val biggest: PhotoSize = photos.sortBy(_.width).last
      val hardSender = sender()
      FilesDao.byId(biggest.file_id) match {
        case Some(prev) =>
          hardSender ! SendMessage(u.message.chatId, "This image is already in DataBase")
        case None =>
          hardSender ! SendMapped(GetFile(biggest.file_id), {
            case Response(_, Right(f@File(_, _, Some(fPath))), _) =>
              hardSender ! FetchFile(f, Boobs.photo, u.message.chat)
              log.info(s"got file $fPath, downloading started")
            case other =>
              log.warning(s"got other response $other")
          })
      }

    case FileFetchingResult(File(fId, _, _), author, t, Success(data)) =>
      log.info(s"file $fId fetched")
      val hash = calculateMD5(data)
      val info = FilesDao.byHash(hash) match {
        case Some(prev) => "This image is already in DataBase"
        case None =>
          Try {
            val files = Boobs(fId, t, hash, Some(author.uber))
            FilesDao.dao.insert(files)
            self.forward(PublishBoobs(files, author))
            "Thx!"
          }.recover {
            case e: DuplicateKey => "This image is already in DataBase"
            case anot => s"Another error ${anot.getMessage}"
          }.get
      }
      sender() ! SendMessage(author.chatId, info)

    case s: FileFetchingResult =>
      log.warning(s"error fetching file: $s")


    case NewUpdate(id, _, u@Update(_, m)) if m.document.isDefined =>
      val doc = m.document.get
      if (doc.mime_type.isDefined) {
        val mime = doc.mime_type.get
        if (Set("image/jpeg", "image/png", "image/gif") contains mime) {
          val hardSender = sender()
          FilesDao.byId(doc.file_id) match {
            case Some(prev) =>
              hardSender ! SendMessage(u.message.chatId, "This image is already in DataBase")
            case None =>
              hardSender ! SendMapped(GetFile(doc.file_id), {
                case Response(_, Right(f@File(_, _, Some(fPath))), _) =>
                  hardSender ! FetchFile(f, Boobs.document, u.message.chat)
                  log.info(s"got file $fPath, downloading started")
                case other =>
                  log.warning(s"got other response $other")
              })
          }
        } else {
          sender() ! HandledUpdate(id, SendMessage(m.chatId, s"Invalid ($mime) mime type."))
        }
      } else {
        sender() ! HandledUpdate(id, SendMessage(m.chatId, "Mime type not defined, I suck"))
      }

    case PublishBoobs(f, owner, None) =>
      self ! PublishBoobs(f, owner, Some(sender()))

    case PublishBoobs(f, owner, Some(worker)) =>
      log.info(s"publishing boobs $f")
      SubsDao.dao.find(MongoDBObject("chatId" -> MongoDBObject("$ne" -> owner))).foreach { listener =>
        worker ! SendBoobsCorrectType(listener.chatId, f)
      }
  }

  def sendBoobs(x: Int, ch: Int): Unit = {
    FilesDao.random(x).foreach(fId =>
      sender() ! SendBoobsCorrectType(ch, fId)
    )
  }

  def sendBoobsToGrade(ch: Chat): Unit = {
    val toMaybe = WaitingLinks.oneWaiting
    log.info(s"next image will be $toMaybe")
    toMaybe.map { to =>
      val req = sender()
      FetchAndCalculateHash(to.link).map { case (_, file) =>
        req ! SendMapped(SendDocument(ch.chatId, Left(file)), {
          case Response(true, Left(id), _) =>
            log.info(s"received $id, I know what it is")
            file.delete()
          case Response(true, Right(m: Message), _) if m.document.isDefined =>
            val telegram_file_id = m.document.get.file_id
            log.info(s"catched new boobs file $telegram_file_id")
            questions += ch.chatId ->(to._id.get, telegram_file_id)
            req ! SendMessage(ch.chatId, s"Grade, /grade_YES /grade_PUBLISH /grade_NO\n/stopgrade if end.", reply_to_message_id = Some(m.message_id))
            file.delete()
          case another =>
            log.warning(s"don't know what is this $another")
            file.delete()
        })
      }
    }.getOrElse {
      sender() ! SendMessage(ch.chatId, "No waiting links")
    }
  }

  def sendHelp(chatId: Int): Unit = {
    val text =
      """
        |Hello, here CyckoBot, I will provide the *best boobs* for You!
        |
        |My boobs database is build by You, send me images, gifs and I save it for
        |You and Your camrades.
        |
        |Commands:
        |boobs - Booooobs!
        |give_me_boobs - Booooobs!
        |make_me_happy - Booooobs!
        |resolve - downloads gif from http link to gif, publishes new file to all subscribers
        |add - like resolve, but without publishing
        |stats - prints some DB statistics
        |subscribe - subscribes this chat to boobs news
        |unsubscribe - unsubscribes
        |delete - as reply to image - deletes boobs from DB
        |help - returns this help
      """.stripMargin
    sender() ! SendMessage(chatId, text, Some("Markdown"))
  }

  def SendBoobsCorrectType(to: Int, fId: Boobs): Command = {
    if (fId.typ == Boobs.document) SendDocument(to, Right(fId.fileId)) else SendPhoto(to, Right(fId.fileId))
  }

  def doSthWithNewFile(chatId: Chat, worker: ActorRef, files: Boobs, publish: Boolean = true) = {
    Try(FilesDao.dao.insert(files)).map(x => log.info(s"saved to db $files")).getOrElse {
      log.warning(s"not saved $files")
    }
    if (publish) {
      hardSelf ! PublishBoobs(files, chatId, Some(worker))
    }
  }

  def resolveLink(link: String, user: Chat, bot: ActorRef, publish: Boolean = true): Unit = {
    FetchAndCalculateHash(link).map { case (hash, tmpFile) =>
      FilesDao.byHash(hash) match {
        case Some(previous) =>
          bot ! SendMessage(user.chatId, "This image is already in DataBase")
          tmpFile.delete()
        case None =>
          bot ! SendMapped(SendDocument(user.chatId, Left(tmpFile)), {
            case Response(true, Left(id), _) =>
              log.info(s"received $id, I know what it is")
              tmpFile.delete()
            case Response(true, Right(m: Message), _) if m.document.isDefined =>
              log.info(s"catched new boobs file ${m.document.get.file_id}")
              doSthWithNewFile(user, bot, Boobs(m.document.get.file_id, Boobs.document, hash, Some(user.uber)), publish)
              tmpFile.delete()
            case another =>
              log.warning(s"don't know what is this $another")
              tmpFile.delete()
          })
      }
    }
  }.recover {
    case UnsupportedBoobsContent(other) =>
      bot ! SendMessage(user.chatId, s"Can't recognize file, only accept image/gif, current is ${other.value}")
    case NoContentInformation =>
      bot ! SendMessage(user.chatId, s"Can't recognize file, service doesn't provide content type... WTF!?")
    case t =>
      t.printStackTrace()
  }

  def calculateMD5(f: Array[Byte]): String = {
    val md = MessageDigest.getInstance("MD5")
    md.update(f)
    DatatypeConverter.printHexBinary(md.digest())
  }

  def calculateMD5(f: JFile): String = {
    import better.files.Cmds.md5
    import better.files.{File => BFile}
    md5(BFile(f.getAbsolutePath))
  }

  log.info("BoobsBot STARTED!")
}
