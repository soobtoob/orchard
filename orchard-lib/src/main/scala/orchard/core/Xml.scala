/**
  * Xml.scala - XML Routines
  * 
  * @author Eric Finster
  * @version 0.1 
  */

package orchard.core

import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer

import Nats._

trait XmlSerializable[A] {
  def toXML(a : A) : xml.Node
  def fromXML(node : xml.Node) : A
}

object XmlSerializable {

  // Just for kicks ...
  def trimText(els : Seq[xml.Node]) = els filterNot (_.isInstanceOf[xml.Text])

  implicit object StringSerializable extends XmlSerializable[String] {
    def toXML(str : String) = xml.Text(str)
    def fromXML(node : xml.Node) : String = node.text.trim
  }

  implicit def polaritySerializable[A : XmlSerializable] : XmlSerializable[Polarity[A]] =
    new XmlSerializable[Polarity[A]] {
      val ev = implicitly[XmlSerializable[A]]
      def toXML(p : Polarity[A]) = 
        p match {
          case Positive => <positive />
          case Negative => <negative />
          case Neutral(a) => <neutral>{ev.toXML(a)}</neutral>
        }

      def fromXML(node : xml.Node) : Polarity[A] =
        node match {
          case <positive /> => Positive
          case <negative /> => Negative
          case <neutral>{content}</neutral> => Neutral(ev.fromXML(content))
          case _ => throw new IllegalArgumentException("Polarity read failed.")
        }
    }

  implicit val tokenSerializable : XmlSerializable[IdentToken] =
    new XmlSerializable[IdentToken] {
      def toXML(ident : IdentToken) =
        ident match {
          case LiteralToken(lit) => <literal>{xml.Text(lit)}</literal>
          case ReferenceToken(id) => <reference>{xml.Text(id)}</reference>
        }

      def fromXML(node : xml.Node) : IdentToken = 
        node match {
          case <literal>{content}</literal> => LiteralToken(content.text)
          case <reference>{content}</reference> => ReferenceToken(content.text)
        }
    }

  implicit def optionSerializable[A : XmlSerializable] : XmlSerializable[Option[A]] =
    new XmlSerializable[Option[A]] {
      val ev = implicitly[XmlSerializable[A]]
      def toXML(o : Option[A]) = 
        o match {
          case None => <none />
          case Some(a) => <some>{ev.toXML(a)}</some>
        }

      def fromXML(node : xml.Node) : Option[A] =
        node match {
          case <none /> => None
          case <some>{content}</some> => Some(ev.fromXML(content))
          case _ => throw new IllegalArgumentException("Option read failed.")
        }
    }

  implicit def pairSerializable[A : XmlSerializable, B : XmlSerializable] : XmlSerializable[(A, B)] = 
    new XmlSerializable[(A, B)] {
      val aIsSerializable = implicitly[XmlSerializable[A]]
      val bIsSerializable = implicitly[XmlSerializable[B]]
      def toXML(pr : (A, B)) = {
        val (a, b) = pr
        <pair><fst>{aIsSerializable.toXML(a)}</fst><snd>{bIsSerializable.toXML(b)}</snd></pair>
      }
      def fromXML(node : xml.Node) = 
        node match {
          case <pair><fst>{aContent}</fst><snd>{bContent}</snd></pair> => {
            (aIsSerializable.fromXML(aContent), bIsSerializable.fromXML(bContent))
          }
        }
    }

  implicit def roseTreeSerializable[A : XmlSerializable, B : XmlSerializable] : XmlSerializable[RoseTree[A, B]] = 
    new XmlSerializable[RoseTree[A, B]] {
      val aIsSerializable = implicitly[XmlSerializable[A]]
      val bIsSerializable = implicitly[XmlSerializable[B]]
      def toXML(t : RoseTree[A, B]) =
        t match {
          case Rose(b) => <rose>{bIsSerializable.toXML(b)}</rose>
          case Branch(a, brs) => <branch>{aIsSerializable.toXML(a)}<branches>{brs map (b => toXML(b))}</branches></branch>
        }
      def fromXML(node : xml.Node) =
        node match {
          case <rose>{content}</rose> => Rose(bIsSerializable.fromXML(content))
          case <branch>{aContent}<branches>{brsContent @ _*}</branches></branch> => {
            val a = aIsSerializable.fromXML(aContent)
            val brs = trimText(brsContent).toVector map (br => fromXML(br))
            Branch(a, brs)
          }
        }
    }

  implicit val identifierSerializable : XmlSerializable[Identifier] = 
    new XmlSerializable[Identifier] {
      def toXML(ident : Identifier) =
        <identifier>{ident.tokens map (token => tokenSerializable.toXML(token))}</identifier>

      def fromXML(node : xml.Node) = 
        node match {
          case <identifier>{tokens @ _*}</identifier> => {
            val idents = trimText(tokens) map (token => 
              tokenSerializable.fromXML(token))

            Identifier(idents.toList)
          }
        }
    }

  implicit val expressionSerializable : XmlSerializable[Expression] =
    new XmlSerializable[Expression] {
      def toXML(expr : Expression) =
        expr match {
          case Variable(ident, isThin) => {
            <variable isThin={isThin.toString}>{
              identifierSerializable.toXML(ident)
            }</variable>
          }
          case FillerFace(ident, filler, isThin) => {
            <fillerface isThin={isThin.toString} filler={filler}>{
              identifierSerializable.toXML(ident)
            }</fillerface>
          }
          case Filler(ident) => {
            <filler>{identifierSerializable.toXML(ident)}</filler>
          }
        }

      def fromXML(node : xml.Node) =
        node match {
          case v @ <variable>{identContent}</variable> => {
            val ident = identifierSerializable.fromXML(identContent)
            val isThin = (v \ "@isThin").text.toBoolean
            Variable(ident, isThin)
          }
          case ff @ <fillerface>{identContent}</fillerface> => {
            val ident = identifierSerializable.fromXML(identContent)
            val isThin = (ff \ "@isThin").text.toBoolean
            val filler = (ff \ "@filler").text
            FillerFace(ident, filler, isThin)
          }
          case f @ <filler>{identContent}</filler> => {
            val ident = identifierSerializable.fromXML(identContent)
            Filler(ident)
          }
        }
    }

  implicit val definitionSerializable : XmlSerializable[Definition] = 
    new XmlSerializable[Definition] {

      def toXML(defn : Definition) = {
        def optToInt(opt : Option[Int]) : Int = 
          opt getOrElse -1

        <definition 
          name={defn.name}
          slevel={optToInt(defn.stabilityLevel).toString}
          ilevel={optToInt(defn.invertibilityLevel).toString}
          ulevel={optToInt(defn.unicityLevel).toString}
          result={defn.result.value.id}
        >{
          defn.environment map (cell => {
            cellSerializable[Expression].toXML(cell)
          })
        }</definition>
      }

      def fromXML(node : xml.Node) = {
        def intToOpt(i : Int) : Option[Int] = if (i < 0) None else Some(i)

        node match {
          case defXml @ <definition>{envCells @ _*}</definition> => {
            val env : Seq[NCell[Expression]] = 
              trimText(envCells) map (n => {
                NCell.cellIsNCell(cellSerializable[Expression].fromXML(n))
              })

            val name : String = (defXml \ "@name").text
            val stabilityLevel : Option[Int] = intToOpt((defXml \ "@slevel").text.toInt)
            val invertibilityLevel : Option[Int] = intToOpt((defXml \ "@ilevel").text.toInt)
            val unicityLevel : Option[Int] = intToOpt((defXml \ "@ulevel").text.toInt)
            val resultName : String = (defXml \ "@result").text
            val result : NCell[Expression] = 
              (env find (expr => expr.value.id == resultName)).get

            new Definition(name, stabilityLevel, invertibilityLevel, unicityLevel, result, env)
          }
        }
      }
    }

  implicit def cellSerializable[A : XmlSerializable] : XmlSerializable[Cell[_ <: Nat, A]] =
    new XmlSerializable[Cell[_ <: Nat, A]] {
      val ev = implicitly[XmlSerializable[A]]

      def toXML(cell : Cell[_ <: Nat, A]) : xml.Node = {
        val cplx = new SimpleMutableComplex(cell)
        val nodes = new ListBuffer[xml.Node]

        cplx forAllCells (c => nodes += c.cellToXML)

        <complex topCell={cplx.topCell.hashCode.toString}>{nodes}</complex>
      }

      def fromXML(node : xml.Node) : Cell[_ <: Nat, A] = {
        node match {
          case <complex>{cells @ _*}</complex> => {
            val topCellId = (node \ "@topCell").text.toInt
            val env = new HashMap[Int, Cell[_ <: Nat, A]]

            for (cell <- trimText(cells)) {
              cell match {
                case <obj>{objContents @ _*}</obj> => {
                  val id = (cell \ "@id").text.toInt
                  val item = ev.fromXML(trimText((objContents \\ "label")(0).descendant)(0))

                  env(id) = Object(item)
                }
                case <cell>{cellContents @ _*}</cell> => {

                  val id = (cell \ "@id").text.toInt
                  val targetId = ((cellContents \\ "target")(0) \ "@ref").text.toInt
                  val item = ev.fromXML(trimText((cellContents \\ "label")(0).descendant)(0))

                  def extractTree[D <: Nat](node : xml.Node) : CellTree[D, A] = {
                    node match {
                      case s @ <seed /> => {
                        val srcId = (s \ "@ref").text.toInt
                        val srcObj = env(srcId).asInstanceOf[ObjectCell[_0, A]]

                        Seed(srcObj).asInstanceOf[CellTree[D, A]]
                      }
                      case l @ <leaf /> => {
                        val leafId = (l \ "@ref").text.toInt
                        val leafCell = env(leafId).asInstanceOf[Cell[D#Pred, A]]

                        Leaf(leafCell).asInstanceOf[CellTree[D, A]]
                      }
                      case g @ <graft>{graftContents @ _*}</graft> => {
                        // Fake it.
                        implicit val hasPred : HasPred[D] = new HasPred[D] { type Pred = D#Pred }

                        val graftId = (g \ "@ref").text.toInt
                        val graftCell = env(graftId).asInstanceOf[Cell[D, A]]
                        val branches = trimText(graftContents).toVector map (n => extractTree[D](n))

                        Graft(graftCell, branches)
                      }
                      case other @ _ => throw new IllegalArgumentException
                    }
                  }

                  val dim : Nat = env(targetId).dimension
                  val srcTree = extractTree[dim.Self](trimText((cellContents \\ "sourcetree")(0).descendant)(0))

                  env(id) = Composite(item, srcTree, env(targetId).value)
                }
                case other @ _ => println("Skipping unrecognized element." ++ other.toString)
              }
            }

            env(topCellId)
          }
          case _ => throw new IllegalArgumentException("Not a complex")
        }
      }
    }
}
