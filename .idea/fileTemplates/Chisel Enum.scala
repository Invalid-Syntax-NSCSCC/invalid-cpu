#if ((${PACKAGE_NAME} && ${PACKAGE_NAME} != ""))package ${PACKAGE_NAME} #end

import chisel3.ChiselEnum

object ${NAME} extends ChiselEnum {
  val _dummy = Value
}