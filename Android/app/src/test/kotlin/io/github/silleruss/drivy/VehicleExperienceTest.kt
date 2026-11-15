package io.github.silleruss.drivy
import org.junit.Assert.*
import org.junit.Test
class VehicleExperienceTest {
 @Test fun syntheticDistanceIsIsolatedAndValidated() {
  assertEquals(5.0, syntheticDrive(0.05, 10.0).experience, 0.00001)
  assertEquals(0.0, syntheticDrive(100.0, 1.0).experience, 0.0)
  assertThrows(IllegalArgumentException::class.java) { syntheticDrive(Double.NaN, 10.0) }
 }
}
