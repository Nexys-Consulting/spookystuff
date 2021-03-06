package org.tribbloid.spookystuff.entity

import java.util.UUID

import org.slf4j.LoggerFactory
import org.tribbloid.spookystuff.actions._
import org.tribbloid.spookystuff.dsl._
import org.tribbloid.spookystuff.entity.PageRow.Signature
import org.tribbloid.spookystuff.expressions._
import org.tribbloid.spookystuff.pages._
import org.tribbloid.spookystuff.utils._
import org.tribbloid.spookystuff.{Const, SpookyContext}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * Created by peng on 8/29/14.
 */
//some guideline: All key parameters are Symbols to align with Spark SQL.
//cells & pages share the same key pool but different data structure
case class PageRow(
                    cells: ListMap[KeyLike, Any] = ListMap(), //TODO: also carry PageUID & property type (Vertex/Edge) for GraphX, ListMap may be slower but has tighter serialization footage
                    pageLikes: Seq[PageLike] = Seq(), // discarded after new page coming in
                    segmentID: UUID = UUID.randomUUID() //keep flattened rows together
                    )
  extends Serializable {

  def pages: Elements[Page] = new Elements(pageLikes.flatMap {
    case page: Page => Some(page)
    case _ => None
  })

  def noPages: Seq[NoPage] = pageLikes.flatMap {
    case noPage: NoPage => Some(noPage)
    case _ => None
  }

  private def resolveKey(keyStr: String): KeyLike = {
    val tempKey = TempKey(keyStr)
    if (cells.contains(tempKey)) tempKey
    else Key(keyStr)
  }

  def getInt(keyStr: String): Option[Int] = {
    this.get(keyStr).flatMap {
      case v: Int => Some(v)
      case _ => None
    }
  }

  def getIntIterable(keyStr: String): Option[Iterable[Int]] = {
    this.getTyped[Iterable[Int]](keyStr)
      .orElse{
      this.getInt(keyStr).map(Seq(_))
    }
  }

  //TempKey precedes ordinary Key
  //T cannot <: AnyVal otherwise will run into https://issues.scala-lang.org/browse/SI-6967
  def getTyped[T <: AnyRef: ClassTag](keyStr: String): Option[T] = {
    val res = this.get(keyStr).flatMap {
      case v: T => Some(v)
      case _ => None
    }
    res
  }

  //T cannot <: AnyVal otherwise will run into https://issues.scala-lang.org/browse/SI-6967
  def getTypedIterable[T <: AnyRef: ClassTag](keyStr: String): Option[Iterable[T]] = {
    val res1 = this.getTyped[Iterable[T]](keyStr)

    val res2 = res1.orElse{
      this.getTyped[T](keyStr).map(Seq(_))
    }
    res2
  }

  def get(keyStr: String): Option[Any] = get(resolveKey(keyStr))

  def get(key: KeyLike): Option[Any] = cells.get(key)

  def getOnlyPage: Option[Page] = {
    val pages = this.pages

    if (pages.size > 1) throw new UnsupportedOperationException("Ambiguous key referring to multiple pages")
    else pages.headOption
  }

  def getPage(keyStr: String): Option[Page] = {

    if (keyStr == Const.onlyPageWildcard) return getOnlyPage

    val pages = this.pages.filter(_.name == keyStr)

    if (pages.size > 1) throw new UnsupportedOperationException("Ambiguous key referring to multiple pages")
    else pages.headOption
  }

  def getUnstructured(keyStr: String): Option[Unstructured] = {

    val page = getPage(keyStr)
    val value = get(keyStr).flatMap {
      case u: Unstructured => Option(u)
      case _ => None
    }

    if (page.nonEmpty && value.nonEmpty) throw new UnsupportedOperationException("Ambiguous key referring to both pages and data")
    else page.orElse(value)
  }

  //replace each '{key} in a string with their respective value in cells
  def replaceInto(
                   str: String,
                   delimiter: String = Const.keyDelimiter
                   ): Option[String] = {
    if (str == null) return None
    if (str.isEmpty) return Some(str)

    val regex = (delimiter+"\\{[^\\{\\}\r\n]*\\}").r

    val result = regex.replaceAllIn(str,m => {
      val original = m.group(0)
      val key = original.substring(2, original.length-1)
      this.get(key) match {
        case Some(v) => v.toString
        case None => return None
      }
    })

    Some(result)
  }

  @transient lazy val signature: Signature = (segmentID, pages.map(_.uid))

  def ordinal(sortKeys: Seq[KeyLike]): Seq[Option[Iterable[Int]]] = {
    val result = sortKeys.map(key => this.getIntIterable(key.name))
    result
  }

  def toMap: Map[String, Any] = this.cells
    .filterKeys(_.isInstanceOf[Key]).map(identity)
    .map( tuple => tuple._1.name -> tuple._2)

  def toJSON: String = {
    import org.tribbloid.spookystuff.views._

    Utils.toJson(this.toMap.canonizeKeysToColumnNames)
  }

  def select(exprs: Expression[Any]*): Option[PageRow] = {
    val newKVs = exprs.map(expr => Key(expr.name) -> expr(this))

    val addKVs = newKVs.filter(_._2.nonEmpty).map(tuple => tuple._1 -> tuple._2.get)
    val removeKVs = newKVs.filter(_._2.isEmpty).map(_._1)

    Some(this.copy(cells = this.cells ++ addKVs -- removeKVs))
  }

  def selectTemp(exprs: Expression[Any]*): Option[PageRow] = {

    val newKVs = exprs.map(expr => TempKey(expr.name) -> expr(this))

    val addKVs = newKVs.filter(_._2.nonEmpty).map(tuple => tuple._1 -> tuple._2.get)
    val removeKVs = newKVs.filter(_._2.isEmpty).map(_._1)

    Some(this.copy(cells = this.cells ++ addKVs -- removeKVs))
  }

  def remove(keys: KeyLike*): PageRow = {
    this.copy(cells = this.cells -- keys)
  }

  private def filterKeys(f: KeyLike => Boolean): PageRow = {
    this.copy(cells = ListMap(this.cells.filterKeys(f).toSeq: _*))
  }

  def clearTemp: PageRow = this.filterKeys(!_.isInstanceOf[TempKey])

  def putPages(others: Seq[PageLike], joinType: JoinType): Option[PageRow] = {
    joinType match {
      case Inner =>
        if (others.isEmpty) None
        else Some(this.copy(pageLikes = others))
      case LeftOuter =>
        Some(this.copy(pageLikes = others))
      case Replace =>
        if (others.isEmpty) Some(this)
        else Some(this.copy(pageLikes = others))
      case Append =>
        Some(this.copy(pageLikes = this.pageLikes ++ others))
      case Merge =>
        val oldUids = this.pageLikes.map(_.uid)
        val newPages = others.filter(newPage => !oldUids.contains(newPage.uid))
        Some(this.copy(pageLikes = this.pageLikes ++ newPages))
    }
  }

  //retain old pageRow,
  //always left
  def flatten(
               keyStr: String,
               ordinalKey: Symbol,
               maxOrdinal: Int,
               left: Boolean
               ): Seq[PageRow] = {

    val key = resolveKey(keyStr)

    import org.tribbloid.spookystuff.views._

    val newCells =cells.flattenKey(key).slice(0, maxOrdinal)

    if (left && newCells.isEmpty) {
      Seq(this.copy(cells = this.cells - key)) //this will make sure you dont't lose anything
    }
    else {
      val result = newCells.map(newCell => this.copy(cells = ListMap(newCell.toSeq: _*)))

      if (ordinalKey == null) result
      else result.zipWithIndex.flatMap{
        tuple =>
          tuple._1.select(Literal(tuple._2) ~+ ordinalKey) //multiple ordinalKey may be inserted sequentially in explore
      }
    }
  }

  //always left, discard old page row
  //warning: sometimes this always lose information regardless of pattern, e.g. all NoPage will be discarded
  //this operation will try to keep NoPages in the first row for lookup
  def flattenPages(
                    pattern: String, //TODO: enable soon
                    ordinalKey: Symbol
                    ): Iterable[PageRow] = {

    val contentRows = this.pages.map{
      page => this.copy(cells = this.cells, pageLikes = Seq(page))
    }

    if (contentRows.isEmpty) {
      Iterable(this.copy(pageLikes = this.noPages))
    }
    else {

      val withOrdinalKey =
        if (ordinalKey == null) contentRows
        else contentRows.zipWithIndex.flatMap{
          tuple =>
            tuple._1.select(Literal(tuple._2) ~+ ordinalKey) //multiple ordinalKey may be inserted sequentially in explore
        }

      withOrdinalKey.zipWithIndex.map{
        tuple =>
          if (tuple._2 == 0) tuple._1.copy(pageLikes = tuple._1.pageLikes ++ this.noPages)
          else tuple._1
      }
    }
  }
}

object PageRow {

  type Signature = (UUID, Iterable[PageUID])

  type Squash = (Trace, Iterable[PageRow])

  def localExplore(
                    stage: ExploreStage,
                    spooky: SpookyContext
                    )(
                    expr: Expression[Any],
                    depthKey: Symbol,
                    depthFromExclusive: Int,
                    depthToInclusive: Int,
                    ordinalKey: Symbol,
                    maxOrdinal: Int
                    )(
                    _traces: Set[Trace], //input of the explore to generate more pages from seeds
                    flattenPagesPattern: Symbol,
                    flattenPagesOrdinalKey: Symbol
                    ): (Iterable[PageRow], ExploreStage) = {

    val total: ArrayBuffer[PageRow] = ArrayBuffer()

    var seeds = stage.seeds
    var traces = stage.traces

    //    assert(seeds.size == 1, "seeds.size="+seeds.size)
    //    assert(depthToInclusive > depthFromExclusive)
    //    assert(traces.size > 1 || depthFromExclusive == 0 , "traces.size="+traces.size)

    for (depth <- depthFromExclusive + 1 to depthToInclusive) {

      //      assert(traces.size == depth)

      val traceToRows = seeds
        .flatMap(_.selectTemp(expr)) //join start: select 1
        .flatMap(_.flatten(expr.name, ordinalKey, maxOrdinal, left = true)) //select 2
        .flatMap { //generate traces
        row =>
          _traces.interpolate(row)
            .filterNot { //if trace or dryrun already exist returns None
            trace =>
              val traceExists = traces.contains(trace) //if trace ...
            val dryrunExists = stage.dryruns.contains(trace.dryrun.toSet) //... or dryrun exist
              traceExists || dryrunExists
          }
            .map(interpolatedTrace => interpolatedTrace -> row)
      }

      val squashes = traceToRows
        .groupBy(_._1)
        .map {
        tuple =>
          val first = PageRow.selectFirstRow(tuple._2.map(_._2), ordinalKey)
          (tuple._1, first)
        //when multiple links on one or more pages leads to the same uri, keep the first one
      }

      traces = traces ++ squashes.map(_._1)

      seeds = squashes
        .flatMap {
        squash =>
          val newPages = squash._1.resolve(spooky)
          val rows = squash._2
          rows.flatMap(_.putPages(newPages, Inner))
      }
        .flatMap {
        row =>
          if (flattenPagesPattern != null) row.flattenPages(flattenPagesPattern.name, flattenPagesOrdinalKey)
          else Seq(row)
      }

      LoggerFactory.getLogger(this.getClass)
        .info(s"found ${seeds.size} new seed(s) after $depth iteration(s) [traces.size = ${traces.size}, total.size = ${total.size}]")
      if (seeds.size == 0) return (total, stage.copy(seeds = seeds, traces = traces))

      //      assert(traces.size == depth+1)

      val newRowsWithDepthKey = if (depthKey != null) seeds.flatMap(_.select(Literal(depth) ~ depthKey))
      else seeds

      total ++= newRowsWithDepthKey
    }

    (total, stage.copy(seeds = seeds, traces = traces))
  }

  def discoverLatestBatch(pages: Iterable[PageLike]): Option[Seq[PageLike]] = {
    //assume that all inputs already has identical backtraces

    if (pages.isEmpty) return None

    val blockIndexToPages = mutable.HashMap[Int, PageLike]()
    for (page <- pages) {
      val oldPage = blockIndexToPages.get(page.uid.blockIndex)
      if (oldPage.isEmpty || page.laterThan(oldPage.get)) blockIndexToPages.put(page.uid.blockIndex, page)
    }
    val sorted = blockIndexToPages.toSeq.sortBy(_._1).map(_._2)

    //extensive sanity check to make sure that none of them are obsolete
    val total = sorted.head.uid.blockTotal
    if (sorted.size < total) return None
    val trunk = sorted.slice(0, sorted.head.uid.blockTotal)

    trunk.foreach{
      page =>
        if (page.uid.blockTotal != total) return None
    }

    Some(sorted.slice(0, sorted.head.uid.blockTotal))
  }

  def selectFirstRow(rows: Iterable[PageRow], key: Symbol): Option[PageRow] = {
    val result = if (rows.isEmpty) None
    else if (key == null) rows.headOption
    else Some(rows.reduce{
      (row1, row2) =>
        import Ordering.Implicits._

        val v1 = row1.getIntIterable(key.name)
        val v2 = row2.getIntIterable(key.name)
        if (v1 <= v2) row1
        else row2
    })

    result
  }
}

//intermediate variable representing a stage in web crawling.
case class ExploreStage(
                         seeds: Iterable[PageRow], //pages that hasn't be been crawled before
                         traces: Set[Trace] = Set(Seq()), //already resolved traces
                         dryruns: Set[Set[Trace]] = Set() //already resolved pages, of which original traces used to resolve them is intractable
                         ) {

  def hasMore = seeds.nonEmpty
}