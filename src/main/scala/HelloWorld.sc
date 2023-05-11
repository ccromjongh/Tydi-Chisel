import chisel3._
import chisel3.util.log2Ceil
import chisel3.experimental.dataview._
import chisel3.internal.firrtl.Width
import chisel3.util.Decoupled
import circt.stage.ChiselStage.emitCHIRRTL

class Element() {}

class Null() extends Element {}
class Group() extends Element {}
class Union() extends Element {}
class BitsEl(w: Width) extends Element {}

class PhysicalStream(val e: Element, val n: Int, val d: Int, val c: Int, val u: Element) extends Bundle {
  require(n >= 1)
  require(1 <= c && c <= 7)

  /** Indicates that the producer has valid data ready
   *
   * @group Signals
   */
  val valid = Output(Bool())

  /** Indicates that the consumer is ready to accept the data this cycle
   *
   * @group Signals
   */
  val ready = Input(Bool())

  private val indexWidth = log2Ceil(n)

  val data = Output(Bits(n.W))
  val last = Output(UInt(d.W))
  val stai = Output(UInt(indexWidth.W))
  val endi = Output(UInt(indexWidth.W))
  val strb = Output(UInt(n.W))
}

class HelloWorldModuleOut extends Module {
  val io = IO(new PhysicalStream(new BitsEl(8.W), n=6, d=2, c=7, u=new Null()))
}

class HelloWorldModuleIn extends Module {
  val io = IO(Flipped(new PhysicalStream(new BitsEl(8.W), n=6, d=2, c=7, u=new Null())))
}

class TopLevelModule extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(64.W))
    val out = Output(SInt(128.W))
  })
  val helloWorldOut = new HelloWorldModuleOut()
  val helloWorldIn = new HelloWorldModuleIn()

  helloWorldIn.io := helloWorldOut.io
}

println(emitCHIRRTL(new TopLevelModule()))
