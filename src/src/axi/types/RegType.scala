package axi.types

object RegType extends Enumeration {
  type RegType = Value
  val BYPASS, SIMPLE_BUFFER, SKID_BUFFER = Value
}
