package com.redislab.provider.redis.rdd

import java.net.InetAddress
import java.util

import org.apache.spark.rdd.RDD
import org.apache.spark._
import redis.clients.jedis._
import redis.clients.util.JedisClusterCRC16

import scala.collection.JavaConversions._
import com.redislab.provider.redis.partitioner._
import com.redislab.provider.RedisConfig
import com.redislab.provider.redis._

class RedisKVRDD(prev: RDD[String],
                 val rddType: String)
    extends RDD[(String, String)](prev) {

  override def getPartitions: Array[Partition] = prev.partitions

  override def compute(split: Partition, context: TaskContext): Iterator[(String, String)] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    val keys = firstParent[String].iterator(split, context)
    rddType match {
      case "kv"   => getKV(nodes, keys);
      case "hash" => getHASH(nodes, keys);
      case "zset" => getZSET(nodes, keys);
    }
  }
  /*
   * node is (IP:String, port:Int, index:Int, range:Int, startSlot:Int, endSlot:Int)
   */
  def mapByNode(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]) = {
    def getNode(key: String) = {
      val slot = JedisClusterCRC16.getSlot(key)
      nodes.filter(node => { node._5 <= slot && node._6 >= slot }).filter(_._3 == 0)(0) // master only
    }
    keys.map(key => (getNode(key), key)).toArray.groupBy(_._1)
  }
  def getKV(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]): Iterator[(String, String)] = {
    mapByNode(nodes, keys).flatMap {
      x =>
        {
          val jedis = new Jedis(x._1._1, x._1._2)
          val pipeline = jedis.pipelined
          x._2.foreach(x => pipeline.`type`(x._2))
          val types = pipeline.syncAndReturnAll
          val stringKeys = (x._2).zip(types).filter(x => (x._2 == "string")).map(x => x._1._2)
          stringKeys.foreach(pipeline.get)
          stringKeys.zip(pipeline.syncAndReturnAll).iterator.asInstanceOf[Iterator[(String, String)]]
        }
    }.iterator
  }
  def getHASH(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]): Iterator[(String, String)] = {
    mapByNode(nodes, keys).flatMap {
      x =>
        {
          val jedis = new Jedis(x._1._1, x._1._2)
          val pipeline = jedis.pipelined
          x._2.foreach(x => pipeline.`type`(x._2))
          val types = pipeline.syncAndReturnAll
          val hashKeys = (x._2).zip(types).filter(x => (x._2 == "hash")).map(x => x._1._2)
          hashKeys.flatMap(jedis.hgetAll).iterator
        }
    }.iterator
  }
  def getZSET(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]): Iterator[(String, String)] = {
    mapByNode(nodes, keys).flatMap {
      x =>
        {
          val jedis = new Jedis(x._1._1, x._1._2)
          val pipeline = jedis.pipelined
          x._2.foreach(x => pipeline.`type`(x._2))
          val types = pipeline.syncAndReturnAll
          val hashKeys = (x._2).zip(types).filter(x => (x._2 == "zset")).map(x => x._1._2)
          hashKeys.flatMap(k => jedis.zrangeWithScores(k, 0, -1)).map(tup => (tup.getElement, tup.getScore.toString)).iterator
        }
    }.iterator
  }
}

class RedisListRDD(prev: RDD[String],
                   val rddType: String)
    extends RDD[String](prev) {

  override def getPartitions: Array[Partition] = prev.partitions

  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    val keys = firstParent[String].iterator(split, context)
    rddType match {
      case "set"  => getSET(nodes, keys);
      case "list" => getLIST(nodes, keys);
    }
  }
  /*
   * node is (IP:String, port:Int, index:Int, range:Int, startSlot:Int, endSlot:Int)
   */
  def mapByNode(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]) = {
    def getNode(key: String) = {
      val slot = JedisClusterCRC16.getSlot(key)
      nodes.filter(node => { node._5 <= slot && node._6 >= slot }).filter(_._3 == 0)(0) // master only
    }
    keys.map(key => (getNode(key), key)).toArray.groupBy(_._1)
  }
  def getSET(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]): Iterator[String] = {
    mapByNode(nodes, keys).flatMap {
      x =>
        {
          val jedis = new Jedis(x._1._1, x._1._2)
          val pipeline = jedis.pipelined
          x._2.foreach(x => pipeline.`type`(x._2))
          val types = pipeline.syncAndReturnAll
          val setKeys = (x._2).zip(types).filter(x => (x._2 == "set")).map(x => x._1._2)
          setKeys.flatMap(jedis.smembers).iterator
        }
    }.iterator
  }
  def getLIST(nodes: Array[(String, Int, Int, Int, Int, Int)], keys: Iterator[String]): Iterator[String] = {
    mapByNode(nodes, keys).flatMap {
      x =>
        {
          val jedis = new Jedis(x._1._1, x._1._2)
          val pipeline = jedis.pipelined
          x._2.foreach(x => pipeline.`type`(x._2))
          val types = pipeline.syncAndReturnAll
          val setKeys = (x._2).zip(types).filter(x => (x._2 == "list")).map(x => x._1._2)
          setKeys.flatMap(jedis.lrange(_, 0, -1)).iterator
        }
    }.iterator
  }
}

class RedisKeysRDD(sc: SparkContext,
                   val redisNode: (String, Int),
                   val keyPattern: String = "*",
                   val partitionNum: Int = 3)
    extends RDD[String](sc, Seq.empty) with Logging with Keys {

  override protected def getPartitions: Array[Partition] = {
    val cnt = 16384 / partitionNum
    (0 until partitionNum).map(i => {
      new RedisPartition(i,
        new RedisConfig(redisNode._1, redisNode._2),
        (cnt * i + 1, if (i != partitionNum - 1) cnt * (i + 1) else 16384)).asInstanceOf[Partition]
    }).toArray
  }

  override def compute(split: Partition, context: TaskContext): Iterator[String] = {
    val partition: RedisPartition = split.asInstanceOf[RedisPartition]
    val sPos = partition.slots._1
    val ePos = partition.slots._2
    val nodes = partition.redisConfig.getNodesBySlots(sPos, ePos)
    getKeys(nodes, sPos, ePos, keyPattern).iterator;
  }
  def getSet(): RDD[String] = {
    new RedisListRDD(this, "set")
  }
  def getList(): RDD[String] = {
    new RedisListRDD(this, "list")
  }
  def getKV(): RDD[(String, String)] = {
    new RedisKVRDD(this, "kv")
  }
  def getHash(): RDD[(String, String)] = {
    new RedisKVRDD(this, "kv")
  }
  def getZSet(): RDD[(String, String)] = {
    new RedisKVRDD(this, "kv")
  }
}

trait Keys {
  def gets(keys: RedisKeysRDD) = {
    keys.collect
  }
  private def isRedisRegex(key: String) = {
    def judge(key: String, escape: Boolean): Boolean = {
      if (key.length == 0)
        return false
      escape match {
        case true => judge(key.substring(1), false);
        case false => {
          key.charAt(0) match {
            case '*'  => true;
            case '?'  => true;
            case '['  => true;
            case '\\' => judge(key.substring(1), true);
            case _    => judge(key.substring(1), false);
          }
        }
      }
    }
    judge(key, false)
  }

  private def scanKeys(jedis: Jedis, params: ScanParams, cursor: String): util.ArrayList[String] = {
    def scankeys(jedis: Jedis, params: ScanParams, cursor: String, scanned: Boolean): util.ArrayList[String] = {
      val keys = new util.ArrayList[String]
      if (scanned && cursor == "0")
        return keys;
      val scan = jedis.scan(cursor, params)
      keys.addAll(scan.getResult)
      keys.addAll(scankeys(jedis, params, scan.getStringCursor, true))
      keys
    }
    scankeys(jedis, params, cursor, false)
  }

  /*
   * node is (IP:String, port:Int, index:Int, range:Int, startSlot:Int, endSlot:Int)
   */
  def getKeys(nodes: Array[(String, Int, Int, Int, Int, Int)], sPos: Int, ePos: Int, keyPattern: String) = {
    val keys = new util.ArrayList[String]()
    if (isRedisRegex(keyPattern)) {
      nodes.foreach(node => {
        val jedis = new Jedis(node._1, node._2)
        val params = new ScanParams().`match`(keyPattern)
        keys.addAll(scanKeys(jedis, params, "0").filter(key => {
          val slot = JedisClusterCRC16.getSlot(key)
          slot >= sPos && slot <= ePos
        }))
      })
    } else {
      val slot = JedisClusterCRC16.getSlot(keyPattern)
      if (slot >= sPos && slot <= ePos)
        keys.add(keyPattern)
    }
    keys
  }
}
