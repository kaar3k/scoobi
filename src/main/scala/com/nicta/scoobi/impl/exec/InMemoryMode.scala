package com.nicta.scoobi
package impl
package exec

import org.apache.commons.logging.LogFactory
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.task.MapContextImpl
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import scala.collection.immutable.VectorBuilder
import scala.collection.JavaConversions._

import core._
import monitor.Loggable._
import impl.plan._
import comp._
import ScoobiConfigurationImpl._
import ScoobiConfiguration._
import org.apache.hadoop.mapreduce.RecordReader
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scalaz.Scalaz._

/**
 * A fast local mode for execution of Scoobi applications.
 */
case class InMemoryMode() extends ShowNode {

  implicit lazy val logger = LogFactory.getLog("scoobi.InMemoryMode")

  def execute(list: DList[_])(implicit sc: ScoobiConfiguration) {
    execute(list.getComp)
  }

  def execute(o: DObject[_])(implicit sc: ScoobiConfiguration): Any = {
    execute(o.getComp)
  }

  def execute(node: CompNode)(implicit sc: ScoobiConfiguration): Any = {
    initAttributable(node).debug("nodes\n", prettyGraph)

    node -> prepare(sc)
    node -> computeValue(sc)
  }

  private
  lazy val prepare: ScoobiConfiguration => CompNode => Unit =
    paramAttr("prepare") { sc: ScoobiConfiguration => { node: CompNode =>
      node match { case n: ProcessNode => n.sinks.foreach(_.outputCheck(sc)); case _ => () }
      children(node).foreach(_ -> prepare(sc))
    }}

  private
  lazy val computeValue: ScoobiConfiguration => CompNode => Any =
    paramAttr("computeValue") { sc: ScoobiConfiguration => (n: CompNode) =>
      (n -> compute(sc)).head
    }

  private
  lazy val compute: ScoobiConfiguration => CompNode => Seq[_] =
    paramAttr("compute") { sc: ScoobiConfiguration => (n: CompNode) =>
      implicit val c = sc
      n match {
        case n: Load        => computeLoad(n)
        case n: Root        => Vector(n.ins.map(_ -> computeValue(c)):_*)
        case n: Return      => Vector(n.in)
        case n: Op          => Vector(n.execute(n.in1 -> computeValue(c),  n.in2 -> computeValue(c)))
        case n: Materialise => Vector(n.in -> compute(c))
        case n: ParallelDo  => saveSinks(computeParallelDo(n), n.sinks)
        case n: GroupByKey  => saveSinks(computeGroupByKey(n), n.sinks)
        case n: Combine     => saveSinks(computeCombine(n)   , n.sinks)
      }
    }

  private def computeLoad(load: Load)(implicit sc: ScoobiConfiguration): Seq[_] = {

    val vb = new VectorBuilder[Any]()
    val job = new Job(new Configuration(sc))
    val source = load.source
    val inputFormat = source.inputFormat.newInstance

    job.setInputFormatClass(source.inputFormat)
    source.inputConfigure(job)

    inputFormat.getSplits(job) foreach { split =>
      val tid = new TaskAttemptID()
      val taskContext = new TaskAttemptContextImpl(job.getConfiguration, tid)
      val rr = inputFormat.createRecordReader(split, taskContext).asInstanceOf[RecordReader[Any, Any]]
      val mapContext = InputOutputContext(new MapContextImpl(job.getConfiguration, tid, rr, null, null, null, split))

      rr.initialize(split, taskContext)

      source.read(rr, mapContext, (a: Any) => vb += WireReaderWriter.wireReaderWriterCopy(a)(load.wf))
      rr.close()
    }

    vb.result.debug("computeLoad")
  }


  private def computeParallelDo(pd: ParallelDo)(implicit sc: ScoobiConfiguration): Seq[_] = {
    val vb = new VectorBuilder[Any]()
    val emitter = new EmitterWriter { def write(v: Any) { vb += v } }

    val (dofn, env) = (pd.dofn, (pd.env -> compute(sc)).head)
    dofn.setupFunction(env)
    (pd.ins.flatMap(_ -> compute(sc))).foreach { v => dofn.processFunction(env, v, emitter) }
    dofn.cleanupFunction(env, emitter)
    vb.result.debug("computeParallelDo")
  }

  private def computeGroupByKey(gbk: GroupByKey)(implicit sc: ScoobiConfiguration): Seq[_] = {
    val in = gbk.in -> compute(sc)
    val gpk = gbk.gpk

    /* Partitioning */
    val partitions: IndexedSeq[Vector[(Any, Any)]] = {
      val numPart = 10    // TODO - set this based on input size? or vary it randomly?
      val vbs = IndexedSeq.fill(numPart)(new VectorBuilder[(Any, Any)]())
      in foreach { case kv @ (k, _) =>
        val p = gpk.partitionKey(k, numPart)
        vbs(p) += kv
      }
      vbs map { _.result() }
    }

    logger.debug("partitions:")
    partitions.zipWithIndex foreach { case (p, ix) => logger.debug(ix + ": " + p) }

    val sorted: IndexedSeq[Vector[(Any, Any)]] = partitions map { (v: Vector[(Any, Any)]) =>
      v.sortBy(_._1)(gpk.toSortOrdering)
    }
    logger.debug("sorted:")
    sorted.zipWithIndex foreach { case (p, ix) => logger.debug(ix + ": " + p) }

    val grouped: IndexedSeq[Vector[(Any, Vector[Any])]] =
      sorted map { kvs =>
        val vbMap = kvs.foldLeft(Vector.empty: Vector[(Any, VectorBuilder[(Any, Any)])]) { case (groups, kv@(k, _)) =>
          groups.lastOption.filter { case (g, _) => gpk.isEqualWithGroup(g, k) } match {
            case Some((_, q)) => {
              q += kv
              groups
            }
            case None =>
              groups :+ {
                val vb = new VectorBuilder[(Any, Any)]()
                vb += kv
                (k, vb)
              }
          }
        }
        vbMap map (_ :-> (_.result.map(_._2)))
      }

    logger.debug("grouped:")
    grouped.zipWithIndex foreach { case (p, ix) => logger.debug(ix + ": " + p) }

    /* Concatenate */
    Vector(grouped.flatten:_*).debug("computeGroupByKey")
  }


  private def computeCombine(combine: Combine)(implicit sc: ScoobiConfiguration): Seq[_] =
    (combine.in -> compute(sc)).map { case (k, vs: Iterable[_]) =>
      (k, combine.combine(vs))
    }.debug("computeCombine")

  private def saveSinks(result: Seq[_], sinks: Seq[Sink])(implicit sc: ScoobiConfiguration): Seq[_] = {
    sinks.foreach { sink =>
      val job = new Job(new Configuration(sc))

      val outputFormat = sink.outputFormat.newInstance()

      FileOutputFormat.setOutputPath(job, sc.temporaryOutputDirectory(job))
      job.setOutputFormatClass(sink.outputFormat)
      job.setOutputKeyClass(sink.outputKeyClass)
      job.setOutputValueClass(sink.outputValueClass)
      job.getConfiguration.set("mapreduce.output.basename", "ch0out0")  // Attempting to be consistent
      sink.configureCompression(job.getConfiguration)
      sink.outputConfigure(job)(sc)

      val tid = new TaskAttemptID()
      val taskContext = new TaskAttemptContextImpl(job.getConfiguration, tid)
      val rw = outputFormat.getRecordWriter(taskContext)
      val oc = outputFormat.getOutputCommitter(taskContext)

      oc.setupJob(job)
      oc.setupTask(taskContext)

      sink.write(result, rw)

      rw.close(taskContext)
      oc.commitTask(taskContext)
      oc.commitJob(job)
    }
    result
  }

}


