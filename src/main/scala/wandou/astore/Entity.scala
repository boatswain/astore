package wandou.astore

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.Stash
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion
import akka.persistence.PersistenceFailure
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericData.Record
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import wandou.astore.script.Scriptable
import wandou.avpath
import wandou.avpath.Evaluator
import wandou.avpath.Evaluator.Ctx
import wandou.avro
import wandou.avro.RecordBuilder

object Entity {
  def props(name: String, schema: Schema, builder: RecordBuilder) = Props(classOf[Entity], name, schema, builder)

  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.id, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (cmd.id.hashCode % 100).toString
  }

  def startSharding(system: ActorSystem, shardName: String, entryProps: Option[Props]) = {
    ClusterSharding(system).start(
      typeName = shardName,
      entryProps = entryProps,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
  }

  final case class OnUpdated(id: String, fieldsBefore: Array[(Schema.Field, Any)], recordAfter: Record)

  private[astore] object Internal {
    final case class Bootstrap(val record: Record)
  }
}

class Entity(val name: String, schema: Schema, builder: RecordBuilder) extends Actor with Stash with ActorLogging with Scriptable {
  import Entity.Internal._

  import context.dispatcher

  private val id = self.path.name
  private val avpathParser = new avpath.Parser()
  private var limitedSize = 30 // TODO

  private var record: Record = _
  private def createRecord() {
    record = builder.build()
  }

  override def preStart {
    super[Actor].preStart
    log.debug("Starting: {} ", id)
    //context.setReceiveTimeout(ReceiveTimeoutSecs)
    createRecord()
    // TODO load persistented data
    self ! Bootstrap(record)
  }

  override def receive: Receive = initial

  def initial: Receive = {
    case Bootstrap(r) =>
      record = r
      context.become(ready)
      unstashAll()
    case _ =>
      stash()
  }

  def ready = normal orElse scriptableReceive

  def normal: Receive = {
    case GetRecord(_) =>
      sender() ! Ctx(record, schema, null)

    case GetRecordAvro(_) =>
      sender() ! avro.avroEncode(record, schema)

    case GetRecordJson(_) =>
      try {
        val json = avro.ToJson.toAvroJsonString(record, schema)
        sender() ! Success(json)
      } catch {
        case ex: Throwable => sender() ! Failure(ex)
      }

    case GetField(_, fieldName) =>
      val field = schema.getField(fieldName)
      if (field != null) {
        sender() ! Ctx(record.get(field.pos), field.schema, field)
      } else {
        // send what ? TODO
      }

    case GetFieldAvro(_, fieldName) =>
      val field = schema.getField(fieldName)
      if (field != null) {
        sender() ! avro.avroEncode(record.get(field.pos), field.schema)
      } else {
        sender() ! Failure(new Exception("no field"))
      }

    case GetFieldJson(_, fieldName) =>
      val field = schema.getField(fieldName)
      if (field != null) {
        try {
          val json = avro.ToJson.toAvroJsonString(record.get(field.pos), field.schema)
          sender() ! Success(json)
        } catch {
          case ex: Throwable => sender() ! Failure(ex)
        }
      } else {
        sender() ! Failure(new Exception("no field"))
      }

    case PutRecord(_, rec) =>
      commitRecord(id, rec, sender(), doLimitSize = true)

    case PutRecordJson(_, json) =>
      try {
        val rec = avro.FromJson.fromJsonString(json, schema).asInstanceOf[Record]
        commitRecord(id, rec, sender(), doLimitSize = true)
      } catch {
        case ex: Throwable =>
          log.error(ex, ex.getMessage)
          sender() ! Failure(ex)
      }

    case PutField(_, fieldName, value) =>
      try {
        val field = schema.getField(fieldName)
        if (field != null) {
          commitField(id, value, field, sender(), doLimitSize = true)
        } else {
          sender() ! Failure(new RuntimeException("Field does not exist: " + fieldName))
        }
      } catch {
        case ex: Throwable =>
          sender() ! Failure(ex)
      }

    case PutFieldJson(_, fieldName, json) =>
      try {
        val field = schema.getField(fieldName)
        if (field != null) {
          val value = avro.FromJson.fromJsonString(json, field.schema)
          commitField(id, value, field, sender(), doLimitSize = true)
        } else {
          sender() ! Failure(new RuntimeException("Field does not exist: " + fieldName))
        }
      } catch {
        case ex: Throwable =>
          sender() ! Failure(ex)
      }

    case Select(_, path) =>
      val ast = avpathParser.parse(path)
      val res = Evaluator.select(record, ast)
      sender() ! res

    case SelectAvro(_, path) =>
      val ast = avpathParser.parse(path)
      val res = Evaluator.select(record, ast)
      sender() ! res // TODO

    case SelectJson(_, path) =>
      val ast = avpathParser.parse(path)
      val res = Evaluator.select(record, ast)
      try {
        val json = res.map {
          case Ctx(value, schema, _, _) => avro.ToJson.toAvroJsonString(value, schema)
        } mkString ("[", ",", "]")
        sender() ! Success(json)
      } catch {
        case ex: Throwable => sender() ! Failure(ex)
      }

    case Update(_, path, value) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.update(copy, ast, value)
      commit(id, copy, ctxs, sender())

    case UpdateJson(_, path, value) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.updateJson(copy, ast, value)
      commit(id, copy, ctxs, sender())

    case Insert(_, path, value) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.insert(copy, ast, value)
      commit(id, copy, ctxs, sender())

    case InsertJson(_, path, value) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.insertJson(copy, ast, value)
      commit(id, copy, ctxs, sender())

    case InsertAll(_, path, xs) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.insertAll(copy, ast, xs)
      commit(id, copy, ctxs, sender())

    case InsertAllJson(_, path, xs) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.insertAllJson(copy, ast, xs)
      commit(id, copy, ctxs, sender())

    case Delete(_, path) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.delete(copy, ast)
      commit(id, copy, ctxs, sender(), false)

    case Clear(_, path) =>
      val copy = new GenericData.Record(record, true)
      val ast = avpathParser.parse(path)
      val ctxs = Evaluator.clear(copy, ast)
      commit(id, copy, ctxs, sender(), false)

    case ReceiveTimeout =>
      log.info("Account got ReceiveTimeout")
    //context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def persisting: Receive = {
    case Success(_) =>
      log.debug("Account persistence success: {}", id)
      context.become(ready)
      unstashAll()

    case PersistenceFailure(payload, sequenceNr, cause) =>
      log.error(cause, "")
      context.become(ready)
      unstashAll()

    case x =>
      log.debug("Entity got {}", x)
      stash()
  }

  private def commitRecord(id: String, toBe: Record, commander: ActorRef, doLimitSize: Boolean) {
    val fields = schema.getFields.iterator
    var updatedFields = List[(Schema.Field, Any)]()
    while (fields.hasNext) {
      val field = fields.next
      if (doLimitSize) {
        avro.toLimitedSize(toBe, field, limitedSize) match {
          case Some(newValue) => updatedFields ::= (field, newValue)
          case None           => updatedFields ::= (field, toBe.get(field.pos))
        }
      } else {
        updatedFields ::= (field, toBe.get(field.pos))
      }
    }
    commit2(id, updatedFields, commander)
  }

  private def commitField(id: String, value: Any, field: Schema.Field, commander: ActorRef, doLimitSize: Boolean) {
    val fields = schema.getFields.iterator
    //TODO if (doLimitSize) avro.limitToSize(rec, field, limitedSize)
    var updatedFields = List((field, value))
    commit2(id, updatedFields, commander)
  }

  private def commit(id: String, toBe: Record, ctxs: List[Evaluator.Ctx], commander: ActorRef, doLimitSize: Boolean = true) {
    val time = System.currentTimeMillis
    // TODO when avpath is ".", topLevelField will be null, it's better to return all topLevelFields
    val updatedFields =
      for (Evaluator.Ctx(value, schema, topLevelField, _) <- ctxs if topLevelField != null) yield {
        if (doLimitSize) {
          avro.toLimitedSize(toBe, topLevelField, limitedSize) match {
            case Some(newValue) => (topLevelField, newValue)
            case None           => (topLevelField, toBe.get(topLevelField.pos))
          }
        } else {
          (topLevelField, toBe.get(topLevelField.pos))
        }
      }

    if (updatedFields.isEmpty) {
      commitRecord(id, toBe, commander, doLimitSize = false)
    } else {
      commit2(id, updatedFields, commander)
    }
  }

  private def commit2(id: String, updatedFields: List[(Schema.Field, Any)], commander: ActorRef) {
    //context.become(persisting)
    persist(id, updatedFields).onComplete {
      case Success(_) =>
        val data = GenericData.get
        val size = updatedFields.size
        val fieldsBefore = Array.ofDim[(Schema.Field, Any)](size)
        var i = 0
        var fields = updatedFields
        while (i < size) {
          val (field, value) = fields.head
          fieldsBefore(i) = (field, data.deepCopy(field.schema, record.get(field.pos)))
          record.put(field.pos, value)
          fields = fields.tail
          i += 1
        }

        commander ! Success(id)
        self ! Success(id)
        self ! Entity.OnUpdated(id, fieldsBefore, record)

      case Failure(ex) =>
        commander ! Failure(ex)
        self ! PersistenceFailure(null, 0, ex) // TODO
    }
  }

  def persist(id: String, modifiedFields: List[(Schema.Field, Any)]): Future[Unit] = Future.successful(())

}