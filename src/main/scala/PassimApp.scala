import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature

import org.lemurproject.galago.tupleflow.Parameters
import org.lemurproject.galago.tupleflow.FakeParameters
import org.lemurproject.galago.core.parse.Document
import org.lemurproject.galago.core.parse.TagTokenizer

import collection.JavaConversions._

case class TokDoc(name: String, text: String, metadata: Map[String,String],
		  terms: Array[String],
		  termCharBegin: Array[Int], termCharEnd: Array[Int])

case class IdSeries(id: Long, series: Long)

object CorpusFun {
  def stringJSON(s: String): scala.collection.immutable.Map[String,String] = {
    val mapper  = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper.readValue(s, classOf[scala.collection.immutable.Map[String, String]])
  }
  def parseDocument(m: scala.collection.immutable.Map[String, String]): TokDoc = {
    var tokp = new Parameters
    tokp.set("fields", List("pb", "w"))
    val tok = new TagTokenizer(new FakeParameters(tokp))

    var d = new Document(m("id"), m("text"))
    tok.tokenize(d)

    TokDoc(m("id"), m("text"),
	   m - "id" - "text",
	   d.terms.toSeq.toArray,
	   d.termCharBegin.map(_.toInt).toArray,
	   d.termCharEnd.map(_.toInt).toArray)
  }
}

object SeriesFun {
  def makeSeries(corpus: RDD[(Long, TokDoc)]): Map[Long,Long] = {
    corpus.mapValues(_.name.split("_")(0)).groupBy(_._2)
      .flatMap(x => {val s = x._2.head._1; x._2.map(p => (p._1, s))}).toLocalIterator.toMap
  }
}
  
class NgramIndexer(val n: Int, val maxSeries: Int) extends Serializable {
  def index(corpus: RDD[(IdSeries, TokDoc)]) = {
    val n_ = n
    val upper = maxSeries * (maxSeries - 1) / 2
    def crossCounts(sizes: Array[Int]): Int = {
      var res: Int = 0
      for ( i <- 0 until sizes.size ) {
	for ( j <- (i + 1) until sizes.size ) {
	  res += sizes(i) * sizes(j)
	}
      }
      res
    }
    corpus.flatMap(d => d._2.terms.zipWithIndex.sliding(n_)
      .map(x => (x.map(y => y._1).mkString("~"),
		 (d._1, x(0)._2))))
		   .groupByKey
		   .filter(x => x._2.size >= 2 && x._2.size <= upper)
		   .filter(x => crossCounts(x._2.map(p => p._1.series).groupBy(identity).map(_._2.size).toArray) <= upper)
		   .mapValues(x => x.groupBy(_._1).toArray.map(p => (p._1, p._2.map(_._2).toArray)).toArray)
  }
}

object PassimApp {
  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("Passim Application")
    val sc = new SparkContext(conf)

    val rawCorpus = sc.textFile(args(0)).map(CorpusFun.stringJSON).map(CorpusFun.parseDocument).zipWithUniqueId.map(x => (x._2, x._1))
    
    val series = SeriesFun.makeSeries(rawCorpus)

    val corpus = rawCorpus.keys.map(series).zip(rawCorpus).map(x => (IdSeries(x._2._1, x._1), x._2._2))

    val maxSeries: Int = 100
    val n: Int = 5
    val minRep: Int = 5

    val indexer = new NgramIndexer(n, maxSeries)

    val pairs = indexer.index(corpus).flatMap(x => for ( a <- x._2; b <- x._2; if a._1.id < b._1.id && a._1.series != b._1.series && a._2.size == 1 && b._2.size == 1 ) yield ((a._1, b._1), (a._2(0), b._2(0), x._2.size))).groupByKey.filter(x => x._2.size >= minRep).mapValues(_.toList.sorted)

    pairs.saveAsTextFile(args(1))
  }
}
