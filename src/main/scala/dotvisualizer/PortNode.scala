// See LICENSE for license details.

package dotvisualizer

case class PortNode(name: String, parentOpt: Option[DotNode], rank: Int=10) extends DotNode {
  def render: String = {
    s"""$absoluteName [shape = "rectangle" label="$name" rank="$rank"]
     """.stripMargin
  }
}
