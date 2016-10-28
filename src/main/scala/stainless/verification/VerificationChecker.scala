/* Copyright 2009-2016 EPFL, Lausanne */

package stainless
package verification

import inox.solvers._

object optParallelVCs extends inox.FlagOptionDef("parallelvcs", false)

object DebugSectionVerification extends inox.DebugSection("verification")

trait VerificationChecker { self =>
  val program: Program

  import program._
  import program.trees._
  import program.symbols._

  implicit val debugSection = DebugSectionVerification

  type VC = verification.VC[program.trees.type]
  val VC = verification.VC

  type VCStatus = verification.VCStatus[program.trees.type]

  type VCResult = verification.VCResult[program.trees.type]
  val VCResult = verification.VCResult

  protected def getTactic(fd: FunDef): Tactic { val program: self.program.type }
  protected def getFactory: SolverFactory { val program: self.program.type }

  def verify(funs: Seq[Identifier], stopWhen: VCResult => Boolean = _ => false): Map[VC, VCResult] = {
    val sf = getFactory

    try {
      ctx.reporter.debug("Generating Verification Conditions...")
      val vcs = generateVCs(funs)

      ctx.reporter.debug("Checking Verification Conditions...")
      checkVCs(vcs, sf, stopWhen)
    } finally {
      sf.shutdown()
    }
  }

  def generateVCs(funs: Seq[Identifier]): Seq[VC] = {
    (for (id <- funs) yield {
      val fd = getFunction(id)
      val tactic = getTactic(fd)

      if (fd.body.isDefined) {
        tactic.generateVCs(id)
      } else {
        Nil
      }
    }).flatten
  }

  private lazy val unknownResult: VCResult = VCResult(VCStatus.Unknown, None, None)
  private lazy val parallelCheck = ctx.options.findOptionOrDefault(optParallelVCs)

  def checkVCs(vcs: Seq[VC], sf: SolverFactory { val program: self.program.type }, stopWhen: VCResult => Boolean = _ => false): Map[VC, VCResult] = {
    var stop = false

    val initMap: Map[VC, VCResult] = vcs.map(vc => vc -> unknownResult).toMap

    // scala doesn't seem to have a nice common super-type of vcs and vcs.par, so these
    // two quasi-identical pieces of code have to remain separate...
    val results = if (parallelCheck) {
      for (vc <- vcs.par if !stop && !ctx.interruptManager.isInterrupted) yield {
        val res = checkVC(vc, sf)
        if (ctx.interruptManager.isInterrupted) ctx.interruptManager.recoverInterrupt()
        stop = stopWhen(res)
        vc -> res
      }
    } else {
      for (vc <- vcs if !stop && !ctx.interruptManager.isInterrupted) yield {
        val res = checkVC(vc, sf)
        if (ctx.interruptManager.isInterrupted) ctx.interruptManager.recoverInterrupt()
        stop = stopWhen(res)
        vc -> res
      }
    }

    initMap ++ results
  }

  private def checkVC(vc: VC, sf: SolverFactory { val program: self.program.type }): VCResult = {
    import SolverResponses._
    val s = sf.getNewSolver

    try {
      ctx.reporter.synchronized {
        ctx.reporter.info(s" - Now considering '${vc.kind}' VC for ${vc.fd} @${vc.getPos}...")
        ctx.reporter.debug(vc.condition.asString)
        ctx.reporter.debug("Solving with: " + s.name)
      }

      val timer = ctx.timers.verification.start()

      s.assertCnstr(Not(vc.condition))

      val res = s.check(Model)

      val time = timer.stop()

      val vcres: VCResult = res match {
        case _ if ctx.interruptManager.isInterrupted =>
          VCResult(VCStatus.Cancelled, Some(s), Some(time))

        case Unknown =>
          VCResult(s match {
            case ts: inox.solvers.combinators.TimeoutSolver => ts.optTimeout match {
              case Some(t) if t < time => VCStatus.Timeout
              case _ => VCStatus.Unknown
            }
            case _ => VCStatus.Unknown
          }, Some(s), Some(time))

        case Unsat =>
          VCResult(VCStatus.Valid, s.getResultSolver, Some(time))

        case SatWithModel(model) =>
          VCResult(VCStatus.Invalid(model), s.getResultSolver, Some(time))
      }

      ctx.reporter.synchronized {
        if (parallelCheck)
          ctx.reporter.info(s" - Result for '${vc.kind}' VC for ${vc.fd} @${vc.getPos}:")

        vcres.status match {
          case VCStatus.Valid =>
            ctx.reporter.info(" => VALID")

          case VCStatus.Invalid(cex) =>
            ctx.reporter.warning(" => INVALID")
            ctx.reporter.warning("Found counter-example:")

            val strings = cex.toSeq.sortBy(_._1.id.name).map {
              case (id, v) => (id.asString, v.asString)
            }

            if (strings.nonEmpty) {
              val max = strings.map(_._1.length).max
              for ((id, v) <- strings) {
                ctx.reporter.warning(("  %-"+max+"s -> %s").format(id, v))
              }
            } else {
              ctx.reporter.warning("  (Empty counter-example)")
            }

          case status =>
            ctx.reporter.warning(" => " + status.name.toUpperCase)
        }
      }

      vcres
    } finally {
      s.free()
    }
  }
}

object VerificationChecker {
  def verify(p: StainlessProgram)(funs: Seq[Identifier]): Map[VC[p.trees.type], VCResult[p.trees.type]] = {
    object checker extends VerificationChecker {
      val program: p.type = p

      val defaultTactic = DefaultTactic(p)
      val inductionTactic = InductionTactic(p)

      protected def getTactic(fd: p.trees.FunDef) =
        if (fd.flags contains "induct") {
          inductionTactic
        } else {
          defaultTactic
        }

      protected def getFactory = solvers.SolverFactory.default(p)
    }

    checker.verify(funs)
  }
}
