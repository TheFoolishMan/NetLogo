// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.window

import org.scalatest.PropSpec
import org.scalatest.prop.PropertyChecks
import org.scalacheck.{ Arbitrary, Gen }

import org.nlogo.api.{ LogoException, MultiErrorHandler, ReporterLogoThunk }
import org.nlogo.agent.{ ConstantSliderConstraint, DynamicSliderConstraint }

import scala.util.{ Failure, Success, Try }

class SliderDataTests extends PropSpec with PropertyChecks {
  val dummyErrorHandler = new MultiErrorHandler {}

  implicit def arbSlider: Arbitrary[SliderData] = Arbitrary(genSlider)
  val genSlider: Gen[SliderData] = for {
    min <- Gen.choose(0, 100)
    max <- Gen.choose(50, 500) // make max usually bigger than min, but not always.
    inc <- Gen.oneOf(0.0, 0.1, 0.01, 1.0, 2.0)
  } yield new SliderData(dummyErrorHandler, min, max, inc)

  // set value tests
  property("after setting constraints, value is never higher than max(min,max)") {
    forAll((s: SliderData, d: Double) =>
      whenever(d > 0) {
        s.value = s.maximum + d
        // some value on the constraint must change, or the value isnt updated.
        // im not actually sure if this is a good rule or not.
        s.setSliderConstraint(new ConstantSliderConstraint(s.minimum - 1, s.maximum, s.increment))
        assert(s.value <= math.max(s.minimum, s.maximum))})}

  //
  // precision tests.
  //
  property("double precision") {
    forAll((r: Int) =>
      whenever(r % 2 == 1) { // get an odd number so it doesn't end in zero.
        // use 1.r
        assertResult(r.toString.length)(
          new SliderData(dummyErrorHandler, minimum = (1 + "." + r).toDouble).precision)})}

  property("precision for ints should always be zero") {
    forAll((i: Int) =>
      whenever(!i.toDouble.toString.contains("E")) {
        assertResult(0)(new SliderData(dummyErrorHandler, minimum = i).precision)})}

  property("sanity precision tests") {
    def testPrecision(d: Double, expected: Int) {
      assertResult(expected)(
        new SliderData(dummyErrorHandler, minimum = d).precision)
    }
    testPrecision(1.1, 1)
    testPrecision(1.0, 0)
    testPrecision(1.01, 2)
    testPrecision(1.10, 1)
    testPrecision(83242938.83242938, 8)
    testPrecision(1.23E0, 2) // 1.23
    testPrecision(1.23E2, 0) // 123.0
    testPrecision(1.23E5, 0) // 12300.0
    testPrecision(1.234E2, 1) // 123.4
    testPrecision(1.2340000, 3) // 1.234

    def testPrecision2(minimum: Double, increment: Double, expected: Int) {
      assertResult(expected)(
        new SliderData(dummyErrorHandler, minimum = minimum, increment = increment).precision)
    }

    // these tests make sure the highest precision is taken
    // because thats what the implementation does.
    // why it does it I'm unsure (JC - 9/27/10)
    testPrecision2(minimum = 0.1, increment = 0.01, expected = 2)
    testPrecision2(minimum = 0.01, increment = 0.1, expected = 2)
    testPrecision2(minimum = 0, increment = 0.1, expected = 1)
    testPrecision2(minimum = 0.1, increment = 0, expected = 1)
    testPrecision2(minimum = 0, increment = 0.001, expected = 3)
    testPrecision2(minimum = 0.001, increment = 0, expected = 3)
  }

  //
  // effective max tests
  //
  property("effective max sanity tests") {
    assertResult(100.0)(
      new SliderData(dummyErrorHandler, minimum = 0,maximum = 100,increment = 1).effectiveMaximum)
    assertResult(50.0)(
      new SliderData(dummyErrorHandler, minimum = 25,maximum = 50,increment = 1).effectiveMaximum)
    assertResult(50.0)(
      new SliderData(dummyErrorHandler, minimum = 0.1,maximum = 50,increment = 0.1).effectiveMaximum)
    assertResult(49.0)(
      new SliderData(dummyErrorHandler, minimum = 25,maximum = 50,increment = 2).effectiveMaximum)
    assertResult(49.0)(
      new SliderData(dummyErrorHandler, minimum = 25,maximum = 50,increment = 3).effectiveMaximum)
    assertResult(46.0)(
      new SliderData(dummyErrorHandler, minimum = 25,maximum = 50,increment = 7).effectiveMaximum)
  }

  property("when min > max, effective max is always min") {
    forAll((min: Double, max: Double) =>
      whenever(min > max) {
        assertResult(min)(
          new SliderData(dummyErrorHandler, minimum = min, maximum = max).effectiveMaximum)})}

  property("when inc == 0, effective max is always min") {
    forAll((min: Double) =>
      assertResult(min)(
        new SliderData(dummyErrorHandler, minimum = min, increment = 0).effectiveMaximum))}

  property("effective maximum is never greater than max") {
    forAll((s: SliderData) =>
      whenever(s.minimum <= s.maximum) {
        assert(s.effectiveMaximum <= s.maximum)})}

  implicit def arbDynConstraint: Arbitrary[DynamicSliderConstraint] = Arbitrary(dynConstraint)
  val dynConstraint: Gen[DynamicSliderConstraint] = {
    def reporterThunk(g: Gen[Double], field: String): Gen[ReporterLogoThunk] =
      g.map(d => dummyThunk(Double.box(d))) :| s"$field reporting a double"

    def errorThunks(field: String) =
      Gen.oneOf[ReporterLogoThunk](
        Gen.const(dummyErrorThunk(new LogoException("problem") {})) :| s"$field throws generic LogoException",
        Gen.const(dummyThunk("abc")) :| s"$field reports a non-double")
    for {
      min <- Gen.oneOf[ReporterLogoThunk](reporterThunk(Gen.choose(0, 49), "min"), errorThunks("min"))
      max <- Gen.oneOf[ReporterLogoThunk](reporterThunk(Gen.choose(50, 500), "max"), errorThunks("max"))
      inc <- Gen.oneOf[ReporterLogoThunk](reporterThunk(Gen.oneOf(0.0, 0.1, 0.01, 1.0, 2.0), "inc"), errorThunks("inc"))
    } yield new DynamicSliderConstraint(min, max, inc)
  }

  property("when dynamic constraint errors, set error on MultiErrorHandler") {
    forAll { (s: SliderData, constraint: DynamicSliderConstraint) =>
      whenever(constraint.maximum.isFailure || constraint.minimum.isFailure || constraint.increment.isFailure) {
        s.setSliderConstraint(constraint)
        assert(dummyErrorHandler.anyErrors)
      }
    }
  }

  def dummyThunk(a: AnyRef): ReporterLogoThunk =
    new ReporterLogoThunk { override def call(): Try[AnyRef] = Success(a) }
  def dummyErrorThunk(e: Exception): ReporterLogoThunk =
    new ReporterLogoThunk { override def call(): Try[AnyRef] = Failure(e) }
}
