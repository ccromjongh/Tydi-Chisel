package tydi_lib.utils

import chisel3.util.{Cat, PopCount, PriorityEncoder, log2Ceil}
import chisel3._
import tydi_lib.{PhysicalStream, SubProcessorSignalDef, TydiEl}

/**
 * Component that can be used to convert a high complexity stream to a low complexity stream.
 *
 * @param template Physical stream to use as a reference for the input stream and partially the output stream.
 * @param memSize  Size of the buffer in terms of total items/lanes.
 */
class ComplexityConverter(val template: PhysicalStream, val memSize: Int) extends SubProcessorSignalDef {
  // Get some information from the template
  private val elWidth = template.elWidth
  private val n = template.n
  private val d = template.d
  private val elType: TydiEl = template.getDataType
  // Create in- and output IO streams based on template
  override val in: PhysicalStream = IO(Flipped(PhysicalStream(elType, n, d = d, c = template.c)))
  override val out: PhysicalStream = IO(PhysicalStream(elType, n, d = d, c = 1))

  in.user := DontCare
  out.user := DontCare

  /** How many bits are required to represent an index of memSize */
  val indexSize: Int = log2Ceil(memSize)
  /** Stores index to write new data to in the register */
  val currentWriteIndex: UInt = RegInit(0.U(indexSize.W))
  val lastWidth: Int = d // Assuming c = 7 here, or that this is the case for all complexities. Todo: Should still verify that.

  // Create actual element storage
  val dataReg: Vec[UInt] = Reg(Vec(memSize, UInt(elWidth.W)))
  val lastReg: Vec[UInt] = Reg(Vec(memSize, UInt(lastWidth.W)))
  val emptyReg: Vec[Bool] = Reg(Vec(memSize, Bool()))
  /** How many elements/lanes are being transferred *out* this cycle */
  val transferOutItemCount: UInt = Wire(UInt(indexSize.W))

  // Shift the whole register file by `transferCount` places by default
  dataReg.zipWithIndex.foreach { case (r, i) =>
    r := dataReg(i.U + transferOutItemCount)
  }
  lastReg.zipWithIndex.foreach { case (r, i) =>
    r := lastReg(i.U + transferOutItemCount)
  }

  /** Signal for storing the indexes the current incoming lanes should write to */
  val writeIndexes: Vec[UInt] = Wire(Vec(n, UInt(indexSize.W)))
  //  val relativeIndexes: Vec[UInt] = Wire(Vec(n, UInt(indexSize.W)))
  // Split incoming data and last signals into indexable vectors
  val lanesIn: Vec[UInt] = VecInit.tabulate(n)(i => in.data((i + 1) * elWidth - 1, i * elWidth))
  val lastsIn: Vec[UInt] = VecInit.tabulate(n)(i => in.last((i + 1) * lastWidth - 1, i * lastWidth))

  /** Register that stores how many first dimension data-series are stored */
  val seriesStored: UInt = RegInit(0.U(indexSize.W))
  // Another possibility is a variable mask I suppose.
  /** A 2D vector of reductions of various slices of the [[in.last]] signal. */
  val reducedLasts: Vec[Vec[UInt]] = VecInit.tabulate(n, n) {
    (i, j) => lastsIn.slice(i, j).reduce(_ | _)
  }
  /** Get which lanes contain MSB lasts */
  val lastsInMsbIndexes: Vec[UInt] = VecInit(
    // Priority encode the last lane with a 1 prepended to fix the PriorityEncoder, subtract 1 again to fix
    lastsIn.map(last => PriorityEncoder(Seq(false.B) ++ last.asBools ++ Seq(true.B)) - 1.U)
  )
  val lastMsbIndex: UInt = RegNext(lastsInMsbIndexes.last, 0.U)
  val significantLastLanes: UInt = VecInit(
    // Prepend the last MSB index to the indexes and run a sliding window to see if the current last MSB has a <= index than the previous entry.
    // Last entry must be > 0 for there to be a new sequence though.
    // Fixme, look-back is now only 1 item. You can start a(n) (empty) sequence later as well.
    (Seq(lastMsbIndex) ++ lastsInMsbIndexes).sliding(2).map { case List(prev, current) =>
      (current <= prev) && prev > 0.U && current > 0.U
    }.toList
  ).asUInt

  val incrementIndexAt: UInt = in.laneValidity | significantLastLanes
  val relativeIndexes: Vec[UInt] = VecInit.tabulate(n)(i => PopCount(incrementIndexAt(0, i)))

  // Calculate & set write indexes
  for ((indexWire, i) <- writeIndexes.zipWithIndex) {
    val ref = if (i == 0) currentWriteIndex else writeIndexes(i - 1)

    // Count which index this lane should get
    // The strobe bit adds 1 for each item, which is why we can remove 1 here, or we would not fill the first slot.
    indexWire := currentWriteIndex + relativeIndexes(i) - 1.U
    // Empty is if the msb is asserted but the lane is not valid
    val isEmpty: Bool = significantLastLanes(i) ^ in.laneValidity(i)
    val isValid = in.laneValidity(i) && in.valid
    when(isValid) {
      dataReg(indexWire) := lanesIn(i)
      lastReg(indexWire) := lastsIn(i)
      emptyReg(indexWire) := isEmpty
    }
  }

  // Index for new cycle is the one after the last index of last cycle - how many lanes we shifted out
  when(in.valid) {
    currentWriteIndex := writeIndexes.last + 1.U - transferOutItemCount
  } otherwise {
    currentWriteIndex := currentWriteIndex - transferOutItemCount
  }

  in.ready := currentWriteIndex < (memSize - n).U // We are ready as long as we have enough space left for a full transfer

  transferOutItemCount := 0.U // Default, overwritten below

  val storedData: Vec[UInt] = VecInit(dataReg.slice(0, n))
  val storedLasts: Vec[UInt] = VecInit(lastReg.slice(0, n))
  //  val storedEmpty: Vec[UInt] = VecInit(emptyReg.slice(0, n))
  var outItemsReadyCount: UInt = Wire(UInt(indexSize.W))

  /** Stores the contents of the least significant bits */
  // The extra true concatenation is to fix the undefined PriorityEncoder behaviour when everything is 0
  val leastSignificantLasts: Seq[Bool] = Seq(false.B) ++ storedLasts.map(_(lastWidth - 1)) ++ Seq(true.B)
  val leastSignificantLastSignal: UInt = leastSignificantLasts.map(_.asUInt).reduce(Cat(_, _))
  // Todo: Check orientation
  val temp: UInt = PriorityEncoder(leastSignificantLasts)
  outItemsReadyCount := Mux(temp > n.U, n.U, temp)

  // Series transferred is the number of last lanes with high MSB
  val transferOutSeriesCount: UInt = Wire(UInt())
  transferOutSeriesCount := 0.U
  val transferInSeriesCount: UInt = lastsIn.map(_(0, 0) & in.ready).reduce(_ + _)

  // When we have at least one series stored and sink is ready
  when(seriesStored > 0.U) {
    when(out.ready) {
      // When transferLength is 0 (no last found) it means the end will come later, transfer n items
      transferOutItemCount := outItemsReadyCount

      // Series transferred is the number of last lanes with high MSB
      transferOutSeriesCount := storedLasts.map(_(0, 0)).reduce(_ + _)
    }

    // Set out stream signals
    out.valid := true.B
    out.data := storedData.reduce(Cat(_, _)) // Re-concatenate all the data lanes
    out.endi := transferOutItemCount - 1.U // Encodes the index of the last valid lane.
    // If there is an empty item/seq, it will for sure be the first one at C=1, else it would not be empty
    when(!emptyReg(0)) {
      out.strb := (1.U << transferOutItemCount) - 1.U
    } otherwise {
      out.strb := 0.U
    }
    // This should be okay since you cannot have an end to a higher dimension without an end to a lower dimension first
    out.last := storedLasts(outItemsReadyCount)
  }.otherwise {
    out.valid := false.B
    out.last := DontCare
    out.endi := DontCare
    out.strb := DontCare
    out.data := DontCare

  }

  seriesStored := seriesStored + transferInSeriesCount - transferOutSeriesCount

  out.stai := 0.U

}
