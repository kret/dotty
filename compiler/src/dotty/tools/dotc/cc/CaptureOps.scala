package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Contexts.*, Annotations.*, Flags.*
import Names.TermName
import ast.{tpd, untpd}
import Decorators.*, NameOps.*
import config.SourceVersion
import config.Printers.capt
import util.Property.Key
import tpd.*
import StdNames.nme
import config.Feature
import collection.mutable
import CCState.*
import reporting.Message
import CaptureSet.{VarState, CompareResult, CompareFailure}

/** Attachment key for capturing type trees */
private val Captures: Key[CaptureSet] = Key()

/** Context property to print root.Fresh(...) as "fresh" instead of "cap" */
val PrintFresh: Key[Unit] = Key()

object ccConfig:

  /** If true, allow mapping capture set variables under captureChecking with maps that are neither
   *  bijective nor idempotent. We currently do now know how to do this correctly in all
   *  cases, though.
   */
  inline val allowUnsoundMaps = false

  /** If enabled, use a special path in recheckClosure for closures
   *  to compare the result tpt of the anonymous functon with the expected
   *  result type. This can narrow the scope of error messages.
   */
  inline val preTypeClosureResults = false

  /** If this and `preTypeClosureResults` are both enabled, disable `preTypeClosureResults`
   *  for eta expansions. This can improve some error messages.
   */
  inline val handleEtaExpansionsSpecially = true

  /** Don't require @use for reach capabilities that are accessed
   *  only in a nested closure. This is unsound without additional
   *  mitigation measures, as shown by unsound-reach-5.scala.
   */
  inline val deferredReaches = false

  /** If true, turn on separation checking */
  def useSepChecks(using Context): Boolean =
    Feature.sourceVersion.stable.isAtLeast(SourceVersion.`3.7`)

  /** Not used currently. Handy for trying out new features */
  def newScheme(using Context): Boolean =
    Feature.sourceVersion.stable.isAtLeast(SourceVersion.`3.8`)

end ccConfig

/** Are we at checkCaptures phase? */
def isCaptureChecking(using Context): Boolean =
  ctx.phaseId == Phases.checkCapturesPhase.id

/** Are we at checkCaptures or Setup phase? */
def isCaptureCheckingOrSetup(using Context): Boolean =
  val ccId = Phases.checkCapturesPhase.id
  val ctxId = ctx.phaseId
  ctxId == ccId || ctxId == ccId - 1

/** A dependent function type with given arguments and result type
 *  TODO Move somewhere else where we treat all function type related ops together.
 */
def depFun(args: List[Type], resultType: Type, isContextual: Boolean, paramNames: List[TermName] = Nil)(using Context): Type =
  val make = MethodType.companion(isContextual = isContextual)
  val mt =
    if paramNames.length == args.length then make(paramNames, args, resultType)
    else make(args, resultType)
  mt.toFunctionType(alwaysDependent = true)

/** An exception thrown if a @retains argument is not syntactically a CaptureRef */
class IllegalCaptureRef(tpe: Type)(using Context) extends Exception(tpe.show)

/** A base trait for data producing addenda to error messages */
trait ErrorNote

/** Capture checking state, which is known to other capture checking components */
class CCState:

  /** Error reprting notes produces since the last call to `test` */
  var notes: List[ErrorNote] = Nil

  def addNote(note: ErrorNote): Unit =
    if !notes.exists(_.getClass == note.getClass) then
      notes = note :: notes

  def test(op: => CompareResult): CompareResult =
    val saved = notes
    notes = Nil
    try op match
      case res: CompareFailure => res.withNotes(notes)
      case res => res
    finally notes = saved

  def testOK(op: => Boolean): CompareResult =
    test(if op then CompareResult.OK else CompareResult.Fail(Nil))

  /** Warnings relating to upper approximations of capture sets with
   *  existentially bound variables.
   */
  val approxWarnings: mutable.ListBuffer[Message] = mutable.ListBuffer()

  private var curLevel: Level = outermostLevel
  private val symLevel: mutable.Map[Symbol, Int] = mutable.Map()

  private var openExistentialScopes: List[MethodType] = Nil

  private var capIsRoot: Boolean = false

  var iterCount = 1

object CCState:

  opaque type Level = Int

  val undefinedLevel: Level = -1

  val outermostLevel: Level = 0

  /** The level of the current environment. Levels start at 0 and increase for
   *  each nested function or class. -1 means the level is undefined.
   */
  def currentLevel(using Context): Level = ccState.curLevel

  /** Perform `op` in the next inner level
   *  @pre We are currently in capture checking or setup
   */
  inline def inNestedLevel[T](inline op: T)(using Context): T =
    val ccs = ccState
    val saved = ccs.curLevel
    ccs.curLevel = ccs.curLevel.nextInner
    try op finally ccs.curLevel = saved

  /** Perform `op` in the next inner level unless `p` holds.
   *  @pre We are currently in capture checking or setup
   */
  inline def inNestedLevelUnless[T](inline p: Boolean)(inline op: T)(using Context): T =
    val ccs = ccState
    val saved = ccs.curLevel
    if !p then ccs.curLevel = ccs.curLevel.nextInner
    try op finally ccs.curLevel = saved

  /** If we are currently in capture checking or setup, and `mt` is a method
   *  type that is not a prefix of a curried method, perform `op` assuming
   *  a fresh enclosing existential scope `mt`, otherwise perform `op` directly.
   */
  inline def inNewExistentialScope[T](mt: MethodType)(op: => T)(using Context): T =
    if isCaptureCheckingOrSetup then
      val ccs = ccState
      val saved = ccs.openExistentialScopes
      if mt.marksExistentialScope then ccs.openExistentialScopes = mt :: ccs.openExistentialScopes
      try op finally ccs.openExistentialScopes = saved
    else
      op

  /** Run `op` under the assumption that `cap` can subsume all other capabilties
   *  except Result capabilities. Every use of this method should be scrutinized
   *  for whether it introduces an unsoundness hole.
   */
  inline def withCapAsRoot[T](op: => T)(using Context): T =
    if isCaptureCheckingOrSetup then
      val ccs = ccState
      val saved = ccs.capIsRoot
      ccs.capIsRoot = true
      try op finally ccs.capIsRoot = saved
    else op

  /** Is `caps.cap` a root capability that is allowed to subsume other capabilities? */
  def capIsRoot(using Context): Boolean = ccState.capIsRoot

  /** The currently opened existential scopes */
  def openExistentialScopes(using Context): List[MethodType] = ccState.openExistentialScopes

  extension (x: Level)
    def isDefined: Boolean = x >= 0
    def <= (y: Level) = (x: Int) <= y
    def nextInner: Level = if isDefined then x + 1 else x

  extension (sym: Symbol)(using Context)
    def ccLevel: Level = ccState.symLevel.getOrElse(sym, -1)
    def recordLevel() = ccState.symLevel(sym) = currentLevel
end CCState

/** The currently valid CCState */
def ccState(using Context): CCState =
  Phases.checkCapturesPhase.asInstanceOf[CheckCaptures].ccState1

extension (tree: Tree)

  /** Map tree with CaptureRef type to its type,
   *  map CapSet^{refs} to the `refs` references,
   *  throw IllegalCaptureRef otherwise
   */
  def toCaptureRefs(using Context): List[CaptureRef] = tree match
    case ReachCapabilityApply(arg) =>
      arg.toCaptureRefs.map(_.reach)
    case ReadOnlyCapabilityApply(arg) =>
      arg.toCaptureRefs.map(_.readOnly)
    case CapsOfApply(arg) =>
      arg.toCaptureRefs
    case _ => tree.tpe.dealiasKeepAnnots match
      case ref: CaptureRef if ref.isTrackableRef =>
        ref :: Nil
      case AnnotatedType(parent, ann)
      if ann.symbol.isRetains && parent.derivesFrom(defn.Caps_CapSet) =>
        ann.tree.toCaptureSet.elems.toList
      case tpe =>
        throw IllegalCaptureRef(tpe) // if this was compiled from cc syntax, problem should have been reported at Typer

  /** Convert a @retains or @retainsByName annotation tree to the capture set it represents.
   *  For efficience, the result is cached as an Attachment on the tree.
   */
  def toCaptureSet(using Context): CaptureSet =
    tree.getAttachment(Captures) match
      case Some(refs) => refs
      case None =>
        val refs = CaptureSet(tree.retainedElems.flatMap(_.toCaptureRefs)*)
          //.showing(i"toCaptureSet $tree --> $result", capt)
        tree.putAttachment(Captures, refs)
        refs

  /** The arguments of a @retains, @retainsCap or @retainsByName annotation */
  def retainedElems(using Context): List[Tree] = tree match
    case Apply(_, Typed(SeqLiteral(elems, _), _) :: Nil) =>
      elems
    case _ =>
      if tree.symbol.maybeOwner == defn.RetainsCapAnnot
      then ref(root.cap) :: Nil
      else Nil

extension (tp: Type)

  /** Is this type a CaptureRef that can be tracked?
   *  This is true for
   *    - all ThisTypes and all TermParamRef,
   *    - stable TermRefs with NoPrefix or ThisTypes as prefixes,
   *    - the root capability `caps.cap`
   *    - abstract or parameter TypeRefs that derive from caps.CapSet
   *    - annotated types that represent reach or maybe capabilities
   */
  final def isTrackableRef(using Context): Boolean = tp match
    case _: (ThisType | TermParamRef) => true
    case tp: TermRef =>
      ((tp.prefix eq NoPrefix)
      || tp.symbol.isField && !tp.symbol.isStatic && tp.prefix.isTrackableRef
      || tp.isCap
      ) && !tp.symbol.isOneOf(UnstableValueFlags)
    case tp: TypeRef =>
      tp.symbol.isType && tp.derivesFrom(defn.Caps_CapSet)
    case tp: TypeParamRef =>
      tp.derivesFrom(defn.Caps_CapSet)
    case root.Result(_) => true
    case AnnotatedType(parent, annot) =>
      defn.capabilityWrapperAnnots.contains(annot.symbol) && parent.isTrackableRef
    case _ =>
      false

  /** The capture set of a type. This is:
    *   - For trackable capture references: The singleton capture set consisting of
    *     just the reference, provided the underlying capture set of their info is not empty.
    *   - For other capture references: The capture set of their info
    *   - For all other types: The result of CaptureSet.ofType
    */
  final def captureSet(using Context): CaptureSet = tp match
    case tp: CaptureRef if tp.isTrackableRef =>
      val cs = tp.captureSetOfInfo
      if cs.isAlwaysEmpty then cs else tp.singletonCaptureSet
    case tp: SingletonCaptureRef => tp.captureSetOfInfo
    case _ => CaptureSet.ofType(tp, followResult = false)

  /** The deep capture set of a type. This is by default the union of all
   *  covariant capture sets embedded in the widened type, as computed by
   *  `CaptureSet.ofTypeDeeply`. If that set is nonempty, and the type is
   *  a singleton capability `x` or a reach capability `x*`, the deep capture
   *  set can be narrowed to`{x*}`.
   */
  def deepCaptureSet(includeTypevars: Boolean)(using Context): CaptureSet =
    val dcs = CaptureSet.ofTypeDeeply(tp.widen.stripCapturing, includeTypevars)
    if dcs.isAlwaysEmpty then tp.captureSet
    else tp match
      case tp @ ReachCapability(_) =>
        tp.singletonCaptureSet
      case ReadOnlyCapability(ref) =>
        ref.deepCaptureSet(includeTypevars).readOnly
      case tp: SingletonCaptureRef if tp.isTrackableRef =>
        tp.reach.singletonCaptureSet
      case _ =>
        tp.captureSet ++ dcs

  def deepCaptureSet(using Context): CaptureSet =
    deepCaptureSet(includeTypevars = false)

  /** A type capturing `ref` */
  def capturing(ref: CaptureRef)(using Context): Type =
    if tp.captureSet.accountsFor(ref) then tp
    else CapturingType(tp, ref.singletonCaptureSet)

  /** A type capturing the capture set `cs`. If this type is already a capturing type
   *  the two capture sets are combined.
   */
  def capturing(cs: CaptureSet)(using Context): Type =
    if (cs.isAlwaysEmpty || cs.isConst && cs.subCaptures(tp.captureSet, VarState.Separate).isOK)
        && !cs.keepAlways
    then tp
    else tp match
      case CapturingType(parent, cs1) => parent.capturing(cs1 ++ cs)
      case _ => CapturingType(tp, cs)

  /** @pre `tp` is a CapturingType */
  def derivedCapturingType(parent: Type, refs: CaptureSet)(using Context): Type = tp match
    case tp @ CapturingType(p, r) =>
      if (parent eq p) && (refs eq r) then tp
      else CapturingType(parent, refs, tp.isBoxed)

  /** If this is a unboxed capturing type with nonempty capture set, its boxed version.
   *  Or, if type is a TypeBounds of capturing types, the version where the bounds are boxed.
   *  The identity for all other types.
   */
  def boxed(using Context): Type = tp.dealias match
    case tp @ CapturingType(parent, refs) if !tp.isBoxed && !refs.isAlwaysEmpty =>
      tp.annot match
        case ann: CaptureAnnotation =>
          assert(!parent.derivesFrom(defn.Caps_CapSet))
          AnnotatedType(parent, ann.boxedAnnot)
        case ann => tp
    case tp: RealTypeBounds =>
      tp.derivedTypeBounds(tp.lo.boxed, tp.hi.boxed)
    case _ =>
      tp

  /** The first element of this path type. Note that class parameter references
   *  are of the form this.C but their pathroot is still this.C, not this.
   */
  final def pathRoot(using Context): Type = tp.dealias match
    case tp1: NamedType
    if tp1.symbol.maybeOwner.isClass && tp1.symbol != defn.captureRoot && !tp1.symbol.is(TypeParam) =>
      tp1.prefix.pathRoot
    case tp1 => tp1

  /** If this part starts with `C.this`, the class `C`.
   *  Otherwise, if it starts with a reference `r`, `r`'s owner.
   *  Otherwise NoSymbol.
   */
  final def pathOwner(using Context): Symbol = pathRoot match
    case tp1: NamedType => tp1.symbol.owner
    case tp1: ThisType => tp1.cls
    case _ => NoSymbol

  final def isParamPath(using Context): Boolean = tp.dealias match
    case tp1: NamedType =>
      tp1.prefix match
        case _: ThisType | NoPrefix =>
          tp1.symbol.is(Param) || tp1.symbol.is(ParamAccessor)
        case prefix => prefix.isParamPath
    case _: ParamRef => true
    case _ => false

  /** If this is a unboxed capturing type with nonempty capture set, its boxed version.
   *  Or, if type is a TypeBounds of capturing types, the version where the bounds are boxed.
   *  The identity for all other types.
   */
  def unboxed(using Context): Type = tp.dealias match
    case tp @ CapturingType(parent, refs) if tp.isBoxed && !refs.isAlwaysEmpty =>
      CapturingType(parent, refs)
    case tp: RealTypeBounds =>
      tp.derivedTypeBounds(tp.lo.unboxed, tp.hi.unboxed)
    case _ =>
      tp

  /** The capture set consisting of all top-level captures of `tp` that appear under a box.
   *  Unlike for `boxed` this also considers parents of capture types, unions and
   *  intersections, and type proxies other than abstract types.
   *  Furthermore, if the original type is a capture ref `x`, it replaces boxed universal sets
   *  on the fly with x*.
   */
  def boxedCaptureSet(using Context): CaptureSet =
    def getBoxed(tp: Type, pre: Type): CaptureSet = tp match
      case tp @ CapturingType(parent, refs) =>
        val pcs = getBoxed(parent, pre)
        if !tp.isBoxed then
          pcs
        else if pre.exists && refs.containsRootCapability then
          val reachRef = if refs.isReadOnly then pre.reach.readOnly else pre.reach
          pcs ++ reachRef.singletonCaptureSet
        else
          pcs ++ refs
      case ref: CaptureRef if ref.isTracked && !pre.exists => getBoxed(ref, ref)
      case tp: TypeRef if tp.symbol.isAbstractOrParamType => CaptureSet.empty
      case tp: TypeProxy => getBoxed(tp.superType, pre)
      case tp: AndType => getBoxed(tp.tp1, pre) ** getBoxed(tp.tp2, pre)
      case tp: OrType => getBoxed(tp.tp1, pre) ++ getBoxed(tp.tp2, pre)
      case _ => CaptureSet.empty
    getBoxed(tp, NoType)

  /** Is the boxedCaptureSet of this type nonempty? */
  def isBoxedCapturing(using Context): Boolean =
    tp match
      case tp @ CapturingType(parent, refs) =>
        tp.isBoxed && !refs.isAlwaysEmpty || parent.isBoxedCapturing
      case tp: TypeRef if tp.symbol.isAbstractOrParamType => false
      case tp: TypeProxy => tp.superType.isBoxedCapturing
      case tp: AndType => tp.tp1.isBoxedCapturing && tp.tp2.isBoxedCapturing
      case tp: OrType => tp.tp1.isBoxedCapturing || tp.tp2.isBoxedCapturing
      case _ => false

  /** Is the box status of `tp` and `tp2` compatible? I.ee  they are
   *  box boxed, or both unboxed, or one of them has an empty capture set.
   */
  def isBoxCompatibleWith(tp2: Type)(using Context): Boolean =
    isBoxedCapturing == tp2.isBoxedCapturing
    || tp.captureSet.isAlwaysEmpty
    || tp2.captureSet.isAlwaysEmpty

  /** If this type is a capturing type, the version with boxed statues as given by `boxed`.
   *  If it is a TermRef of a capturing type, and the box status flips, widen to a capturing
   *  type that captures the TermRef.
   */
  def forceBoxStatus(boxed: Boolean)(using Context): Type = tp.widenDealias match
    case tp @ CapturingType(parent, refs) if tp.isBoxed != boxed =>
      val refs1 = tp match
        case ref: CaptureRef if ref.isTracked || ref.isReach || ref.isReadOnly =>
          ref.singletonCaptureSet
        case _ => refs
      CapturingType(parent, refs1, boxed)
    case _ =>
      tp

  /** Map capturing type to their parents. Capturing types accessible
   *  via dealising are also stripped.
   */
  def stripCapturing(using Context): Type = tp.dealiasKeepAnnots match
    case CapturingType(parent, _) =>
      parent.stripCapturing
    case atd @ AnnotatedType(parent, annot) =>
      atd.derivedAnnotatedType(parent.stripCapturing, annot)
    case _ =>
      tp

  /** Is type known to be always pure by its class structure?
   *  In that case, adding a capture set to it would not make sense.
   */
  def isAlwaysPure(using Context): Boolean = tp.dealias match
    case tp: (TypeRef | AppliedType) =>
      val sym = tp.typeSymbol
      if sym.isClass then sym.isPureClass
      else tp.superType.isAlwaysPure
    case tp: TypeProxy =>
      tp.superType.isAlwaysPure
    case tp: AndType =>
      tp.tp1.isAlwaysPure || tp.tp2.isAlwaysPure
    case tp: OrType =>
      tp.tp1.isAlwaysPure && tp.tp2.isAlwaysPure
    case _ =>
      false

  /** Is this a type extending `Mutable` that has update methods? */
  def isMutableType(using Context): Boolean =
    tp.derivesFrom(defn.Caps_Mutable)
    && tp.membersBasedOnFlags(Mutable | Method, EmptyFlags)
      .exists(_.hasAltWith(_.symbol.isUpdateMethod))

  /** Knowing that `tp` is a function type, is it an alias to a function other
   *  than `=>`?
   */
  def isAliasFun(using Context): Boolean = tp match
    case AppliedType(tycon, _) => !defn.isFunctionSymbol(tycon.typeSymbol)
    case _ => false

  /** Tests whether all CapturingType parts of the type that are traversed for
   *  dcs computation satisfy at least one of two conditions:
   *   1. They decorate classes that extend the given capability class `cls`, or
   *   2. Their capture set is constant and consists only of capabilities
   *      the derive from `cls` in the sense of `derivesFromCapTrait`.
   */
  def derivesFromCapTraitDeeply(cls: ClassSymbol)(using Context): Boolean =
    val accumulate = new DeepTypeAccumulator[Boolean]:
      def capturingCase(acc: Boolean, parent: Type, refs: CaptureSet) =
        this(acc, parent)
        && (parent.derivesFromCapTrait(cls)
            || refs.isConst && refs.elems.forall(_.derivesFromCapTrait(cls)))
      def abstractTypeCase(acc: Boolean, t: TypeRef, upperBound: Type) =
        this(acc, upperBound)
    accumulate(true, tp)

  /** Tests whether the type derives from capability class `cls`. */
  def derivesFromCapTrait(cls: ClassSymbol)(using Context): Boolean = tp.dealiasKeepAnnots match
    case tp: (TypeRef | AppliedType) =>
      val sym = tp.typeSymbol
      if sym.isClass then sym.derivesFrom(cls)
      else tp.superType.derivesFromCapTrait(cls)
    case ReachCapability(tp1) =>
      tp1.widen.derivesFromCapTraitDeeply(cls)
    case ReadOnlyCapability(tp1) =>
      tp1.derivesFromCapTrait(cls)
    case tp: (TypeProxy & ValueType) =>
      tp.superType.derivesFromCapTrait(cls)
    case tp: AndType =>
      tp.tp1.derivesFromCapTrait(cls) || tp.tp2.derivesFromCapTrait(cls)
    case tp: OrType =>
      tp.tp1.derivesFromCapTrait(cls) && tp.tp2.derivesFromCapTrait(cls)
    case _ =>
      false

  def derivesFromCapability(using Context): Boolean = derivesFromCapTrait(defn.Caps_Capability)
  def derivesFromMutable(using Context): Boolean = derivesFromCapTrait(defn.Caps_Mutable)
  def derivesFromSharedCapability(using Context): Boolean = derivesFromCapTrait(defn.Caps_SharedCapability)

  /** Drop @retains annotations everywhere */
  def dropAllRetains(using Context): Type = // TODO we should drop retains from inferred types before unpickling
    val tm = new TypeMap:
      def apply(t: Type) = t match
        case AnnotatedType(parent, annot) if annot.symbol.isRetains =>
          apply(parent)
        case _ =>
          mapOver(t)
    tm(tp)

  /** If `x` is a capture ref, its maybe capability `x?`, represented internally
   *  as `x @maybeCapability`. `x?` stands for a capability `x` that might or might
   *  not be part of a capture set. We have `{} <: {x?} <: {x}`. Maybe capabilities
   *  cannot be propagated between sets. If `a <: b` and `a` acquires `x?` then
   *  `x` is propagated to `b` as a conservative approximation.
   *
   *  Maybe capabilities should only arise for capture sets that appear in invariant
   *  position in their surrounding type. They are similar to TypeBunds types, but
   *  restricted to capture sets. For instance,
   *
   *      Array[C^{x?}]
   *
   *  should be morally equivalent to
   *
   *      Array[_ >: C^{} <: C^{x}]
   *
   *   but it has fewer issues with type inference.
   */
  def maybe(using Context): CaptureRef = tp match
    case tp @ AnnotatedType(_, annot) if annot.symbol == defn.MaybeCapabilityAnnot => tp
    case _ => MaybeCapability(tp)

  /** If `x` is a capture ref, its reach capability `x*`, represented internally
   *  as `x @reachCapability`. `x*` stands for all capabilities reachable through `x`".
   *  We have `{x} <: {x*} <: dcs(x)}` where the deep capture set `dcs(x)` of `x`
   *  is the union of all capture sets that appear in covariant position in the
   *  type of `x`. If `x` and `y` are different variables then `{x*}` and `{y*}`
   *  are unrelated.
   *
   *  Reach capabilities cannot wrap read-only capabilities or maybe capabilities.
   *  We have
   *      (x.rd).reach = x*.rd
   *      (x.rd)?      = (x*)?
   */
  def reach(using Context): CaptureRef = tp match
    case tp @ AnnotatedType(tp1: CaptureRef, annot)
    if annot.symbol == defn.MaybeCapabilityAnnot =>
      tp1.reach.maybe
    case tp @ AnnotatedType(tp1: CaptureRef, annot)
    if annot.symbol == defn.ReadOnlyCapabilityAnnot =>
      tp1.reach.readOnly
    case tp @ AnnotatedType(tp1: CaptureRef, annot)
    if annot.symbol == defn.ReachCapabilityAnnot =>
      tp
    case _ =>
      ReachCapability(tp)

  /** If `x` is a capture ref, its read-only capability `x.rd`, represented internally
   *  as `x @readOnlyCapability`. We have {x.rd} <: {x}. If `x` is a reach capability `y*`,
   *  then its read-only version is `x.rd*`.
   *
   *  Read-only capabilities cannot wrap maybe capabilities
   *  but they can wrap reach capabilities. We have
   *      (x?).readOnly = (x.rd)?
   */
  def readOnly(using Context): CaptureRef = tp match
    case tp @ AnnotatedType(tp1: CaptureRef, annot)
    if annot.symbol == defn.MaybeCapabilityAnnot =>
      tp1.readOnly.maybe
    case tp @ AnnotatedType(tp1: CaptureRef, annot)
    if annot.symbol == defn.ReadOnlyCapabilityAnnot =>
      tp
    case _ =>
      ReadOnlyCapability(tp)

  /** If `x` is a capture ref, replace all no-flip covariant occurrences of `cap`
   *  in type `tp` with `x*`.
   */
  def withReachCaptures(ref: Type)(using Context): Type =
    object narrowCaps extends TypeMap:
      var change = false
      def apply(t: Type) =
        if variance <= 0 then t
        else t.dealias match
          case t @ CapturingType(p, cs) if cs.containsRootCapability =>
            change = true
            val reachRef = if cs.isReadOnly then ref.reach.readOnly else ref.reach
            t.derivedCapturingType(apply(p), reachRef.singletonCaptureSet)
          case t @ AnnotatedType(parent, ann) =>
            // Don't map annotations, which includes capture sets
            t.derivedAnnotatedType(this(parent), ann)
          case t @ FunctionOrMethod(args, res) =>
            if args.forall(_.isAlwaysPure) then
              // Also map existentials in results to reach capabilities if all
              // preceding arguments are known to be always pure
              t.derivedFunctionOrMethod(args, apply(root.resultToFresh(res)))
            else
              t
          case _ =>
            mapOver(t)
    end narrowCaps

    ref match
      case ref: CaptureRef if ref.isTrackableRef =>
        val tp1 = narrowCaps(tp)
        if narrowCaps.change then
          capt.println(i"narrow $tp of $ref to $tp1")
          tp1
        else
          tp
      case _ =>
        tp
  end withReachCaptures

  /** Does this type contain no-flip covariant occurrences of `cap`? */
  def containsCap(using Context): Boolean =
    val acc = new TypeAccumulator[Boolean]:
      def apply(x: Boolean, t: Type) =
        x
        || variance > 0 && t.dealiasKeepAnnots.match
          case t @ CapturingType(p, cs) if cs.containsCap =>
            true
          case t @ AnnotatedType(parent, ann) =>
            // Don't traverse annotations, which includes capture sets
            this(x, parent)
          case _ =>
            foldOver(x, t)
    acc(false, tp)

  def level(using Context): Level =
    tp match
    case tp: TermRef => tp.symbol.ccLevel
    case tp: ThisType => tp.cls.ccLevel.nextInner
    case _ => undefinedLevel

extension (tp: MethodType)
  /** A method marks an existential scope unless it is the prefix of a curried method */
  def marksExistentialScope(using Context): Boolean =
    !tp.resType.isInstanceOf[MethodOrPoly]

extension (cls: ClassSymbol)

  def pureBaseClass(using Context): Option[Symbol] =
    cls.baseClasses.find: bc =>
      defn.pureBaseClasses.contains(bc)
      || bc.is(CaptureChecked)
          && bc.givenSelfType.dealiasKeepAnnots.match
            case CapturingType(_, refs) => refs.isAlwaysEmpty
            case RetainingType(_, refs) => refs.isEmpty
            case selfType =>
              isCaptureChecking  // At Setup we have not processed self types yet, so
                                 // unless a self type is explicitly given, we can't tell
                                 // and err on the side of impure.
              && selfType.exists && selfType.captureSet.isAlwaysEmpty

  def baseClassHasExplicitNonUniversalSelfType(using Context): Boolean =
    cls.baseClasses.exists: bc =>
      bc.is(CaptureChecked)
      && bc.givenSelfType.exists
      && !bc.givenSelfType.captureSet.isUniversal

  def matchesExplicitRefsInBaseClass(refs: CaptureSet)(using Context): Boolean =
    cls.baseClasses.tail.exists: bc =>
      val selfType = bc.givenSelfType
      bc.is(CaptureChecked) && selfType.exists && selfType.captureSet.elems == refs.elems

extension (sym: Symbol)

  /** This symbol is one of `retains` or `retainsCap` */
  def isRetains(using Context): Boolean =
    sym == defn.RetainsAnnot || sym == defn.RetainsCapAnnot

  /** This symbol is one of `retains`, `retainsCap`, or`retainsByName` */
  def isRetainsLike(using Context): Boolean =
    isRetains || sym == defn.RetainsByNameAnnot

  /** A class is pure if:
   *   - one its base types has an explicitly declared self type with an empty capture set
   *   - or it is a value class
   *   - or it is an exception
   *   - or it is one of Nothing, Null, or String
   */
  def isPureClass(using Context): Boolean = sym match
    case cls: ClassSymbol =>
      cls.pureBaseClass.isDefined || defn.pureSimpleClasses.contains(cls)
    case _ =>
      false

  /** Does this symbol allow results carrying the universal capability?
   *  Currently this is true only for function type applies (since their
   *  results are unboxed) and `erasedValue` since this function is magic in
   *  that is allows to conjure global capabilies from nothing (aside: can we find a
   *  more controlled way to achieve this?).
   *  But it could be generalized to other functions that so that they can take capability
   *  classes as arguments.
   */
  def allowsRootCapture(using Context): Boolean =
    sym == defn.Compiletime_erasedValue
    || defn.isFunctionClass(sym.maybeOwner)

  /** When applying `sym`, would the result type be unboxed?
   *  This is the case if the result type contains a top-level reference to an enclosing
   *  class or method type parameter and the method does not allow root capture.
   *  If the type parameter is instantiated to a boxed type, that type would
   *  have to be unboxed in the method's result.
   */
  def unboxesResult(using Context): Boolean =
    def containsEnclTypeParam(tp: Type): Boolean = tp.strippedDealias match
      case tp @ TypeRef(pre: ThisType, _) => tp.symbol.is(Param)
      case tp: TypeParamRef => true
      case tp: AndOrType => containsEnclTypeParam(tp.tp1) || containsEnclTypeParam(tp.tp2)
      case tp: RefinedType => containsEnclTypeParam(tp.parent) || containsEnclTypeParam(tp.refinedInfo)
      case _ => false
    containsEnclTypeParam(sym.info.finalResultType)
    && !sym.allowsRootCapture
    && !defn.isPolymorphicAfterErasure(sym)
    && !defn.isTypeTestOrCast(sym)

  /** It's a parameter accessor that is not annotated @constructorOnly or @uncheckedCaptures */
  def isRefiningParamAccessor(using Context): Boolean =
    sym.is(ParamAccessor)
    && {
      val param = sym.owner.primaryConstructor.paramNamed(sym.name)
      !param.hasAnnotation(defn.ConstructorOnlyAnnot)
      && !param.hasAnnotation(defn.UntrackedCapturesAnnot)
    }

  def hasTrackedParts(using Context): Boolean =
    !CaptureSet.ofTypeDeeply(sym.info).isAlwaysEmpty

  /** `sym` is annotated @use or it is a type parameter with a matching
   *  @use-annotated term parameter that contains `sym` in its deep capture set.
   */
  def isUseParam(using Context): Boolean =
    sym.hasAnnotation(defn.UseAnnot)
    || sym.is(TypeParam)
        && sym.owner.rawParamss.nestedExists: param =>
            param.is(TermParam) && param.hasAnnotation(defn.UseAnnot)
            && param.info.deepCaptureSet.elems.exists:
                case c: TypeRef => c.symbol == sym
                case _ => false

  def isUpdateMethod(using Context): Boolean =
    sym.isAllOf(Mutable | Method, butNot = Accessor)

  def isReadOnlyMethod(using Context): Boolean =
    sym.is(Method, butNot = Mutable | Accessor) && sym.owner.derivesFrom(defn.Caps_Mutable)

  def isInReadOnlyMethod(using Context): Boolean =
    if sym.is(Method) && sym.owner.isClass then isReadOnlyMethod
    else sym.owner.isInReadOnlyMethod

extension (tp: AnnotatedType)
  /** Is this a boxed capturing type? */
  def isBoxed(using Context): Boolean = tp.annot match
    case ann: CaptureAnnotation => ann.boxed
    case _ => false

  def rootAnnot: root.Annot = (tp.annot: @unchecked) match
    case ann: root.Annot => ann

/** Drop retains annotations in the type. */
class CleanupRetains(using Context) extends TypeMap:
  def apply(tp: Type): Type =
    tp match
      case AnnotatedType(tp, annot) if annot.symbol == defn.RetainsAnnot || annot.symbol == defn.RetainsByNameAnnot =>
        RetainingType(tp, Nil, byName = annot.symbol == defn.RetainsByNameAnnot)
      case _ => mapOver(tp)

/** A typemap that follows aliases and keeps their transformed results if
 *  there is a change.
 */
trait FollowAliasesMap(using Context) extends TypeMap:
  var follow = true    // Used for debugging so that we can compare results with and w/o following.
  def mapFollowingAliases(t: Type): Type =
    val t1 = t.dealiasKeepAnnots
    if follow && (t1 ne t) then
      val t2 = apply(t1)
      if t2 ne t1 then t2
      else t
    else mapOver(t)

/** An extractor for `caps.reachCapability(ref)`, which is used to express a reach
 *  capability as a tree in a @retains annotation.
 */
object ReachCapabilityApply:
  def unapply(tree: Apply)(using Context): Option[Tree] = tree match
    case Apply(reach, arg :: Nil) if reach.symbol == defn.Caps_reachCapability => Some(arg)
    case _ => None

/** An extractor for `caps.readOnlyCapability(ref)`, which is used to express a read-only
 *  capability as a tree in a @retains annotation.
 */
object ReadOnlyCapabilityApply:
  def unapply(tree: Apply)(using Context): Option[Tree] = tree match
    case Apply(ro, arg :: Nil) if ro.symbol == defn.Caps_readOnlyCapability => Some(arg)
    case _ => None

/** An extractor for `caps.capsOf[X]`, which is used to express a generic capture set
 *  as a tree in a @retains annotation.
 */
object CapsOfApply:
  def unapply(tree: TypeApply)(using Context): Option[Tree] = tree match
    case TypeApply(capsOf, arg :: Nil) if capsOf.symbol == defn.Caps_capsOf => Some(arg)
    case _ => None

abstract class AnnotatedCapability(annotCls: Context ?=> ClassSymbol):
  def apply(tp: Type)(using Context): AnnotatedType =
    assert(tp.isTrackableRef, i"not a trackable ref: $tp")
    tp match
      case AnnotatedType(_, annot) =>
        assert(!unwrappable.contains(annot.symbol), i"illegal combination of derived capabilities: $annotCls over ${annot.symbol}")
      case _ =>
    tp match
      case tp: CaptureRef => tp.derivedRef(annotCls)
      case _ => AnnotatedType(tp, Annotation(annotCls, util.Spans.NoSpan))

  def unapply(tree: AnnotatedType)(using Context): Option[CaptureRef] = tree match
    case AnnotatedType(parent: CaptureRef, ann) if ann.hasSymbol(annotCls) => Some(parent)
    case _ => None

  protected def unwrappable(using Context): Set[Symbol]
end AnnotatedCapability

/** An extractor for `ref @maybeCapability`, which is used to express
 *  the maybe capability `ref?` as a type.
 */
object MaybeCapability extends AnnotatedCapability(defn.MaybeCapabilityAnnot):
  protected def unwrappable(using Context) = Set()

/** An extractor for `ref @readOnlyCapability`, which is used to express
 *  the read-only capability `ref.rd` as a type.
 */
object ReadOnlyCapability extends AnnotatedCapability(defn.ReadOnlyCapabilityAnnot):
  protected def unwrappable(using Context) = Set(defn.MaybeCapabilityAnnot)

/** An extractor for `ref @annotation.internal.reachCapability`, which is used to express
 *  the reach capability `ref*` as a type.
 */
object ReachCapability extends AnnotatedCapability(defn.ReachCapabilityAnnot):
  protected def unwrappable(using Context) = Set(defn.MaybeCapabilityAnnot, defn.ReadOnlyCapabilityAnnot)

/** An extractor for all kinds of function types as well as method and poly types.
 *  It includes aliases of function types such as `=>`. TODO: Can we do without?
 *  @return  1st half: The argument types or empty if this is a type function
 *           2nd half: The result type
 */
object FunctionOrMethod:
  def unapply(tp: Type)(using Context): Option[(List[Type], Type)] = tp match
    case defn.FunctionOf(args, res, isContextual) => Some((args, res))
    case mt: MethodType => Some((mt.paramInfos, mt.resType))
    case mt: PolyType => Some((Nil, mt.resType))
    case defn.RefinedFunctionOf(rinfo) => unapply(rinfo)
    case _ => None

/** If `tp` is a function or method, a type of the same kind with the given
 *  argument and result types.
 */
extension (self: Type)
  def derivedFunctionOrMethod(argTypes: List[Type], resType: Type)(using Context): Type = self match
    case self @ AppliedType(tycon, args) if defn.isNonRefinedFunction(self) =>
      val args1 = argTypes :+ resType
      if args.corresponds(args1)(_ eq _) then self
      else self.derivedAppliedType(tycon, args1)
    case self @ defn.RefinedFunctionOf(rinfo) =>
      val rinfo1 = rinfo.derivedFunctionOrMethod(argTypes, resType)
      if rinfo1 eq rinfo then self
      else if rinfo1.isInstanceOf[PolyType] then self.derivedRefinedType(refinedInfo = rinfo1)
      else rinfo1.toFunctionType(alwaysDependent = true)
    case self: MethodType =>
      self.derivedLambdaType(paramInfos = argTypes, resType = resType)
    case self: PolyType =>
      assert(argTypes.isEmpty)
      self.derivedLambdaType(resType = resType)
    case _ =>
      self

/** An extractor for a contains argument */
object ContainsImpl:
  def unapply(tree: TypeApply)(using Context): Option[(Tree, Tree)] =
    tree.fun.tpe.widen match
      case fntpe: PolyType if tree.fun.symbol == defn.Caps_containsImpl =>
        tree.args match
          case csArg :: refArg :: Nil => Some((csArg, refArg))
          case _ => None
      case _ => None

/** An extractor for a contains parameter */
object ContainsParam:
  def unapply(sym: Symbol)(using Context): Option[(TypeRef, CaptureRef)] =
    sym.info.dealias match
      case AppliedType(tycon, (cs: TypeRef) :: (ref: CaptureRef) :: Nil)
      if tycon.typeSymbol == defn.Caps_ContainsTrait
          && cs.typeSymbol.isAbstractOrParamType => Some((cs, ref))
      case _ => None

/** A class encapsulating the assumulator logic needed for `CaptureSet.ofTypeDeeply`
 *  and `derivesFromCapTraitDeeply`.
 *  NOTE: The traversal logic needs to be in sync with narrowCaps in CaptureOps, which
 *  replaces caps with reach capabilties. There are two exceptions, however.
 *   - First, invariant arguments. These have to be included to be conservative
 *     in dcs but must be excluded in narrowCaps.
 *   - Second, unconstrained type variables are handled specially in `ofTypeDeeply`.
 */
abstract class DeepTypeAccumulator[T](using Context) extends TypeAccumulator[T]:
  val seen = util.HashSet[Symbol]()

  protected def capturingCase(acc: T, parent: Type, refs: CaptureSet): T

  protected def abstractTypeCase(acc: T, t: TypeRef, upperBound: Type): T

  def apply(acc: T, t: Type) =
    if variance < 0 then acc
    else t.dealias match
      case t @ CapturingType(p, cs1) =>
        capturingCase(acc, p, cs1)
      case t: TypeRef if t.symbol.isAbstractOrParamType && !seen.contains(t.symbol) =>
        seen += t.symbol
        abstractTypeCase(acc, t, t.info.bounds.hi)
      case AnnotatedType(parent, _) =>
        this(acc, parent)
      case t @ FunctionOrMethod(args, res) =>
        if args.forall(_.isAlwaysPure) then this(acc, root.resultToFresh(res))
        else acc
      case _ =>
        foldOver(acc, t)
end DeepTypeAccumulator

