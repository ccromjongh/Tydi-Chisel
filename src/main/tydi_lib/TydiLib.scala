package tydi_lib

import chisel3._
import chisel3.util.{Cat, PopCount, PriorityEncoder, log2Ceil}
import chisel3.internal.firrtl.Width

sealed trait Element extends Bundle {
  val isStream: Boolean = false
  val elWidth: Int = 0
  def getWidth: Int
  def getElements: Seq[Data]

  /** Gets data elements without streams. I.e. filters out any `Element`s that are also streams */
  def getDataElements: Seq[Data] = getElements.filter(x => x match {
    case x: Element => !x.isStream
    case _ => true
  })

  /** Recursive way of getting only the data elements of the stream. */
  def getDataElementsRec: Seq[Data] = {
    val els = getDataElements
    val mapped = els.flatMap(x => x match {
      case x: Element => x.getDataElementsRec
      case x: Bundle => x.getElements
      case _ => x :: Nil
    })
    mapped
  }

  def getDataConcat: UInt = {
    // Filter out any `Element`s that are also streams.
    // `.asUInt` also does recursive action but we don't want sub-streams to be included.
    getDataElementsRec.map(_.asUInt).reduce((prev, new_) => Cat(prev, new_))
  }
}

sealed class Null extends Element

object Null {
  def apply(): Null = new Null
}

class Group() extends Bundle with Element

class Union() extends Element {
  //  def getWidth: Int = {
  //    elWidth
  //  }
  val tag = UInt(0.W)
  val value = UInt(0.W)

  //  def getElements: Seq[Data] = Seq[Data](UInt(elWidth.W))
}

class BitsEl(override val width: Width) extends Element {
  val value: UInt = Bits(width)
}

object BitsEl {
  def apply(width: Width): BitsEl = new BitsEl(width)
}

abstract class PhysicalStreamBase(private val e: Element, val n: Int, val d: Int, val c: Int, private val u: Element) extends Element {
  override val isStream: Boolean = true

  require(n >= 1)
  require(1 <= c && c <= 7)

  def elementType = e.cloneType

  /** Indicates that the producer has valid data ready
   *
   * @group Signals
   */
  val valid: Bool = Output(Bool())

  /** Indicates that the consumer is ready to accept the data this cycle
   *
   * @group Signals
   */
  val ready: Bool = Input(Bool())

  private val indexWidth = log2Ceil(n)

  val data: Data

  val lastWidth: Int = if (c == 7) d * n else d
  val last: UInt = Output(UInt(lastWidth.W))
  val stai: UInt = Output(UInt(indexWidth.W))
  val endi: UInt = Output(UInt(indexWidth.W))
  val strb: UInt = Output(UInt(n.W))
}

class PhysicalStream(private val e: Element, n: Int = 1, d: Int = 0, c: Int, private val u: Element = Null()) extends PhysicalStreamBase(e, n, d, c, u) {
  override val elWidth: Int = e.getDataElementsRec.map(_.getWidth).sum
  val data: UInt = Output(UInt((elWidth*n).W))

  // Stream mounting function
  def :=[T <: Element](bundle: PhysicalStreamDetailed[T]): Unit = {
    // This could be done with a :<>= but I like being explicit here to catch possible errors.
    if (!bundle.r) {
      this.endi := bundle.endi
      this.stai := bundle.stai
      this.strb := bundle.strb
      this.last := bundle.last
      this.valid := bundle.valid
      bundle.ready := this.ready
      this.data := bundle.getDataConcat
    } else {
      bundle.endi := this.endi
      bundle.stai := this.stai
      bundle.strb := this.strb
      bundle.last := this.last
      bundle.valid := this.valid
      this.ready := bundle.ready
      // Connect data bitvector back to bundle
      bundle.getDataElementsRec.foldLeft(0)((i, data) => {
        val width = data.getWidth
        data := this.data(i+width-1, i)
        i + width
      })
    }
  }
}

object PhysicalStream {
  def apply(e: Element, n: Int = 1, d: Int = 0, c: Int, u: Element = Null()): PhysicalStream = new PhysicalStream(e, n, d, c, u)
}

class PhysicalStreamDetailed[T <: Element](private val e: T, n: Int = 1, d: Int = 0, c: Int, var r: Boolean = false, private val u: Element = Null()) extends PhysicalStreamBase(e, n, d, c, u) {
  val data: Vec[T] = Output(Vec(n, e))

  override def getDataConcat: UInt = data.map(_.getDataConcat).reduce(Cat(_, _))

  override def getDataElementsRec: Seq[Data] = data.flatMap(_.getDataElementsRec)

  def el: T = data(0)

  def flip: PhysicalStreamDetailed[T] = {
    r = !r
    this
  }

  def toPhysical: PhysicalStream = {
    val flip = r
    val stream = new PhysicalStream(e, n, d, c, u)
    val io = IO(if (flip) Flipped(stream) else stream)
    io := this
    io
  }
}

object PhysicalStreamDetailed {
  def apply[T <: Element](e: T, n: Int = 1, d: Int = 0, c: Int, r: Boolean = false, u: Element = Null()): PhysicalStreamDetailed[T] = Wire(new PhysicalStreamDetailed(e, n, d, c, r, u))
}

class TydiModule extends Module {
  def mount[T <: Element](bundle: PhysicalStreamDetailed[T], io: PhysicalStream): Unit = {
    io := bundle
  }
}

class Buffer(dataWidth: Width, lastWidth: Width) extends Bundle {
  val data: UInt = UInt(dataWidth)
  val last: UInt = UInt(lastWidth)
}

class ComplexityConverter[T <: Element](val template: PhysicalStream) extends TydiModule {
  // Get some information from the template
  private val elWidth = template.elWidth
  private val n = template.n
  private val d = template.d
  val elType = template.elementType
  // Create in- and output IO streams based on template
  val in: PhysicalStream = IO(Flipped(PhysicalStream(elType, n, d = d, c = template.c)))
  val out: PhysicalStream = IO(PhysicalStream(elType, n, d = d, c = 1))

  val memSize = 20
  val indexSize: Int = log2Ceil(memSize)
  val currentIndex: UInt = RegInit(0.U(indexSize.W))
  val lastWidth: Int = d  // Assuming c = 7 here, or that this is the case for all complexities. Todo: Should still verify that.
  val bufferType = new Buffer(elWidth.W, lastWidth.W)
  // Create actual element storage
  val reg: Vec[Buffer] = Reg(Vec(n, bufferType))

  /** Signal for storing the indexes the current incoming lanes should write to */
  val indexes: Vec[UInt] = Wire(Vec(n, UInt(indexSize.W)))
  // Split incoming data and last signals into indexable vectors
  val lanesSeq: Seq[UInt] = Seq.tabulate(n)(i => in.data((i+1)*elWidth-1, i*elWidth))
  val lastSeq: Seq[UInt] = Seq.tabulate(n)(i => in.last((i+1)*lastWidth-1, i*lastWidth))
  val lanes: Vec[UInt] = VecInit(lanesSeq)
  val lasts: Vec[UInt] = VecInit(lastSeq)

  /** Register that stores how many first dimension data-series are stored */
  val seriesStored: UInt = RegInit(0.U(indexSize.W))

  // Calculate & set write indexes
  indexes.zipWithIndex.foreach(x => {
    val isValid = in.strb(x._2)
    // Count which index this lane should get
    x._1 := currentIndex + PopCount(in.strb(x._2, 0))
    when(isValid) {
      reg(x._1).data := lanes(x._2)
      reg(x._1).last := lasts(x._2)
    }
  })

  // Fixme: Can I assume that last will not be high if it is not valid?
  seriesStored := seriesStored + lasts.map(_(0, 0)).reduce(_+_)

  val transferCount: UInt = UInt(indexSize.W)
  transferCount := 0.U

  // When we have at least one series stored
  when (seriesStored > 0.U) {
    val stored = VecInit(reg.slice(0, n))
    val storedLasts = stored.map(_.last)
    val lastLastBitIndex: Int = lastWidth-1
    /** Stores the contents of the least significant bits */
    val leastSignificantLast = storedLasts.map(_(lastLastBitIndex))
    // Todo: Check orientation
    val transferLength = PriorityEncoder(leastSignificantLast.reverse)

    // When transferLength is 0 it means the end will come later, transfer all
    out.valid := true.B
    out.data := stored.map(_.data).reduce(Cat(_, _))  // Re-concatenate all the data lanes
    transferCount := Mux(transferLength === 0.U, n.U, transferLength)
    out.endi := transferCount
    // This should be okay since you cannot have an end to a higher dimension without an end to a lower dimension first
    out.last := stored(transferLength).last
  } .otherwise {
    out.valid := false.B
  }
  out.stai := 0.U

}