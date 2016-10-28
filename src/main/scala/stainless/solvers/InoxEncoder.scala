/* Copyright 2009-2016 EPFL, Lausanne */

package stainless
package solvers

import inox.ast._

trait InoxEncoder extends ProgramEncoder {
  val sourceProgram: Program
  val t: inox.trees.type = inox.trees

  override protected def encodedProgram: inox.Program { val trees: t.type } = {
    import sourceProgram.trees._
    import sourceProgram.symbols._

    inox.InoxProgram(sourceProgram.ctx, t.NoSymbols
      .withADTs(sourceProgram.symbols.adts.values.toSeq.map(encoder.transform))
      .withFunctions(sourceProgram.symbols.functions.values.toSeq.map { fd =>
        if (fd.flags contains Extern) {
          val Lambda(Seq(vd), post) = fd.postOrTrue
          encoder.transform(fd.copy(fullBody = fd.precondition match {
            case Some(pre) =>
              Require(pre, Choose(vd, post))

            case None =>
              Choose(vd, post)
          }, flags = fd.flags - Extern))
        } else {
          encoder.transform(fd)
        }
      }))
  }

  import t.dsl._

  protected val arrayID = FreshIdentifier("array")
  protected val arr  = FreshIdentifier("arr")
  protected val size = FreshIdentifier("size")

  protected val arrayInvariantID = FreshIdentifier("array_inv")
  protected val arrayInvariant = mkFunDef(arrayInvariantID)("A") { case Seq(aT) => (
    Seq("array" :: T(arrayID)(aT)), t.BooleanType, { case Seq(array) =>
      array.getField(size) >= E(0)
    })
  }

  protected val arrayADT: t.ADTConstructor = {
    val tdef = t.TypeParameterDef(t.TypeParameter(FreshIdentifier("A")))
    new t.ADTConstructor(arrayID, Seq(tdef), None,
      Seq(t.ValDef(arr, t.MapType(t.Int32Type, tdef.tp)), t.ValDef(size, t.Int32Type)),
      Set(t.HasADTInvariant(arrayInvariantID))
    )
  }

  protected override val extraADTs: Seq[t.ADTDefinition] = Seq(arrayADT)

  val encoder: TreeEncoder
  val decoder: TreeDecoder

  protected class TreeEncoder extends TreeTransformer {
    val s: InoxEncoder.this.s.type = InoxEncoder.this.s
    val t: InoxEncoder.this.t.type = InoxEncoder.this.t

    import sourceProgram.symbols._

    override def transform(e: s.Expr): t.Expr = e match {
      case m: s.MatchExpr =>
        transform(matchToIfThenElse(m))

      case s.NoTree(tpe) =>
        throw new inox.FatalError("Unexpected empty tree: " + e)

      case s.Error(tpe, desc) =>
        t.Variable(FreshIdentifier("error: " + desc, true), transform(tpe)).copiedFrom(e)

      case s.Require(pred, body) =>
        t.Assume(transform(pred), transform(body)).copiedFrom(e)

      case s.Ensuring(s.Require(pred, body), s.Lambda(Seq(res), post)) =>
        val vd = t.ValDef(res.id, transform(res.tpe))
        t.Assume(transform(pred),
          t.Let(vd, transform(body), t.Assume(transform(post), vd.toVariable)))

      case s.Ensuring(body, s.Lambda(Seq(res), post)) =>
        val vd = t.ValDef(res.id, transform(res.tpe))
        t.Let(vd, transform(body), t.Assume(transform(post), vd.toVariable))

      case s.Assert(pred, error, body) =>
        t.Assume(transform(pred), transform(body))

      case s.FiniteArray(elems, base) =>
        t.ADT(t.ADTType(arrayID, Seq(transform(base))), Seq(
          t.FiniteMap(
            elems.zipWithIndex.map { case (e, i) => t.IntLiteral(i) -> transform(e) },
            transform(simplestValue(base)),
            t.Int32Type,
            transform(base)),
          t.IntLiteral(elems.size)
        ))

      case s.LargeArray(elems, dflt, size, base) =>
        t.ADT(t.ADTType(arrayID, Seq(transform(dflt.getType))), Seq(
          t.FiniteMap(
            elems.toSeq.map(p => t.IntLiteral(p._1) -> transform(p._2)),
            transform(dflt),
            t.Int32Type,
            transform(base)),
          transform(size)
        ))

      case s.ArraySelect(array, index) =>
        t.MapApply(t.ADTSelector(transform(array), arr), transform(index))

      case s.ArrayUpdated(array, index, value) =>
        val na = transform(array)
        t.ADT(transform(array.getType).asInstanceOf[t.ADTType], Seq(
          t.MapUpdated(t.ADTSelector(na, arr), transform(index), transform(value)),
          t.ADTSelector(na, size)
        ))

      case s.ArrayLength(array) =>
        t.ADTSelector(transform(array), size)

      case _ => super.transform(e)
    }

    override def transform(tpe: s.Type): t.Type = tpe match {
      case s.ArrayType(base) => t.ADTType(arrayID, Seq(transform(base)))
      case _ => super.transform(tpe)
    }
  }

  protected class TreeDecoder extends TreeTransformer {
    val s: InoxEncoder.this.t.type = InoxEncoder.this.t
    val t: InoxEncoder.this.s.type = InoxEncoder.this.s

    /** Transform back from encoded array ADTs into stainless arrays.
      * Note that this translation should only occur on models returned by
      * the Inox solver, so we can assume that the array adts have the
      * shape:
      * {{{
      *    Array(FiniteMap(pairs, _, _), IntLiteral(size))
      * }}}
      * where all keys in {{{pairs}}} are IntLiterals. This assumption also
      * holds on the output of [[SymbolsEncoder#TreeEncoder.transform(Expr)]].
      */
    override def transform(e: s.Expr): t.Expr = e match {
      case s.ADT(
        s.ADTType(`arrayID`, Seq(base)),
        Seq(s.FiniteMap(elems, dflt, _, _), s.IntLiteral(size))
      ) if size <= 10 =>
        val elemsMap = elems.toMap
        t.FiniteArray((0 until size).toSeq.map {
          i => transform(elemsMap.getOrElse(s.IntLiteral(i), dflt))
        }, transform(base))

      case s.ADT(s.ADTType(`arrayID`, Seq(base)), Seq(s.FiniteMap(elems, dflt, _, _), size)) =>
        t.LargeArray(
          elems.map { case (s.IntLiteral(i), e) => i -> transform(e) }.toMap,
          transform(dflt),
          transform(size),
          transform(base)
        )

      case _ => super.transform(e)
    }

    override def transform(tpe: s.Type): t.Type = tpe match {
      case s.ADTType(`arrayID`, Seq(base)) => t.ArrayType(transform(base))
      case _ => super.transform(tpe)
    }
  }
}

object InoxEncoder {
  def apply(p: StainlessProgram): InoxEncoder { val sourceProgram: p.type } = new {
    val sourceProgram: p.type = p
  } with InoxEncoder {
    object encoder extends TreeEncoder
    object decoder extends TreeDecoder
  }
}
