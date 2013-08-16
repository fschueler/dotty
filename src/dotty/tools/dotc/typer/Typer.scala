package dotty.tools
package dotc
package typer

import core._
import ast._
import Trees._
import Constants._
import StdNames._
import Scopes._
import Denotations._
import Inferencing._
import Contexts._
import Symbols._
import Types._
import SymDenotations._
import Annotations._
import Names._
import NameOps._
import Flags._
import Decorators._
import ErrorReporting._
import Applications.{FunProtoType, PolyProtoType}
import EtaExpansion.etaExpand
import util.Positions._
import util.SourcePosition
import collection.mutable
import annotation.tailrec
import language.implicitConversions

trait TyperContextOps { ctx: Context => }

object Typer {

  import tpd.{cpy => _, _}

  object BindingPrec {
    val definition = 4
    val namedImport = 3
    val wildImport = 2
    val packageClause = 1
    val nothingBound = 0
    def isImportPrec(prec: Int) = prec == namedImport || prec == wildImport
  }

  implicit class TreeDecorator(tree: Tree) {
    def qualifierType(implicit ctx: Context): Type = tree.tpe match {
      case tpe: TermRef if !tpe.symbol.isStable => tpe.info
      case tpe: TypeRef => tpe.info
      case tpe => tpe
    }
  }

  case class StateFul[T](value: T, state: TyperState) {
    def commit()(implicit ctx: Context): T = {
      state.commit()
      value
    }
  }

  class SelectionProto(name: Name, tp: Type) extends RefinedType(WildcardType, name)(_ => tp)

  object AnySelectionProto extends SelectionProto(nme.WILDCARD, WildcardType)
}

class Typer extends Namer with Applications with Implicits {

  import Typer._
  import tpd.{cpy => _, _}
  import untpd.cpy

  /** A temporary data item valid for a single typed ident:
   *  The set of all root import symbols that have been
   *  encountered as a qualifier of an import so far.
   *  Note: It would be more proper to move importedFromRoot into typedIdent.
   *  We should check that this has no performance degradation, however.
   */
  private var importedFromRoot: Set[Symbol] = Set()

  def selectionType(site: Type, name: Name, pos: Position)(implicit ctx: Context): Type = {
    val ref =
      if (name == nme.CONSTRUCTOR) site.decl(name)
      else site.member(name)
    if (ref.exists) NamedType(site, name).withDenot(ref)
    else {
      if (!site.isErroneous)
        ctx.error(
          if (name == nme.CONSTRUCTOR) i"$site does not have a constructor"
          else i"$name is not a member of $site", pos)
      ErrorType
    }
  }

  def checkedSelectionType(qual1: Tree, tree: untpd.RefTree)(implicit ctx: Context): Type = {
    val ownType = selectionType(qual1.qualifierType, tree.name, tree.pos)
    if (!ownType.isError) checkAccessible(ownType, qual1.isInstanceOf[Super], tree.pos)
    ownType
  }

  def checkValue(tpe: Type, proto: Type, pos: Position)(implicit ctx: Context): Unit =
    if (!proto.isInstanceOf[SelectionProto]) {
      val sym = tpe.termSymbol
      if ((sym is Package) || (sym is JavaModule)) ctx.error(i"$sym is not a value", pos)
    }

  def checkAccessible(tpe: Type, superAccess: Boolean, pos: Position)(implicit ctx: Context): Type = tpe match {
    case tpe: NamedType =>
      val pre = tpe.prefix
      val name = tpe.name
      val d = tpe.denot.accessibleFrom(pre, superAccess)
      if (!d.exists) {
        val alts = tpe.denot.alternatives.map(_.symbol).filter(_.exists)
        val where = pre.typeSymbol
        val what = alts match {
          case Nil =>
            name.toString
          case sym :: Nil =>
            if (sym.owner == where) sym.show else sym.showLocated
          case _ =>
            i"none of the overloaded alternatives named $name"
        }
        val whyNot = new StringBuffer
        val addendum =
          alts foreach (_.isAccessibleFrom(pre, superAccess, whyNot))
        ctx.error(i"$what cannot be accessed in $where.$whyNot")
        ErrorType
      } else tpe withDenot d
    case _ =>
      tpe
  }

  /** The qualifying class
   *  of a this or super with prefix `qual`.
   *  packageOk is equal false when qualifying class symbol
   */
  def qualifyingClass(tree: untpd.Tree, qual: Name, packageOK: Boolean)(implicit ctx: Context): Symbol =
    ctx.owner.enclosingClass.ownersIterator.find(o => qual.isEmpty || o.isClass && o.name == qual) match {
      case Some(c) if packageOK || !(c is Package) =>
        c
      case _ =>
        ctx.error(
          if (qual.isEmpty) tree.show + " can be used only in a class, object, or template"
          else qual.show + " is not an enclosing class", tree.pos)
        NoSymbol
    }

  /** Attribute an identifier consisting of a simple name or an outer reference.
   *
   *  @param tree      The tree representing the identifier.
   *  Transformations: (1) Prefix class members with this.
   *                   (2) Change imported symbols to selections
   *
   */
  def typedIdent(tree: untpd.Ident, pt: Type)(implicit ctx: Context): Tree = {
    val name = tree.name

    /** Is this import a root import that has been shadowed by an explicit
     *  import in the same program?
     */
    def isDisabled(imp: ImportInfo, site: Type): Boolean = {
      val qualSym = site.termSymbol
      if (defn.RootImports contains qualSym) {
        if (imp.rootImport && (importedFromRoot contains qualSym)) return true
        importedFromRoot += qualSym
      }
      false
    }

    /** Does this identifier appear as a constructor of a pattern? */
    def isPatternConstr =
      if (ctx.mode.isExpr && (ctx.outer.mode is Mode.Pattern))
        ctx.outer.tree match {
          case Apply(`tree`, _) => true
          case _ => false
        }
      else false

    /** A symbol qualifies if it exists and is not stale. Stale symbols
     *  are made to disappear here. In addition,
     *  if we are in a constructor of a pattern, we ignore all definitions
     *  which are methods (note: if we don't do that
     *  case x :: xs in class List would return the :: method)
     *  unless they are stable or are accessors (the latter exception is for better error messages)
     */
    def qualifies(sym: Symbol): Boolean = !(
         sym.isAbsent
      || isPatternConstr && (sym is (Method, butNot = Accessor))
      )

    /** Find the denotation of enclosing `name` in given context `ctx`.
     *  @param previous    A denotation that was found in a more deeply nested scope,
     *                     or else `NoDenotation` if nothing was found yet.
     *  @param prevPrec    The binding precedence of the previous denotation,
     *                     or else `nothingBound` if nothing was found yet.
     *  @param prevCtx     The context of the previous denotation,
     *                     or else `NoContext` if nothing was found yet.
     */
    def findRef(previous: Type, prevPrec: Int, prevCtx: Context)(implicit ctx: Context): Type = {
      import BindingPrec._

      /** A string which explains how something was bound; Depending on `prec` this is either
       *      imported by <tree>
       *  or  defined in <symbol>
       */
      def bindingString(prec: Int, whereFound: Context, qualifier: String = "") =
        if (prec == wildImport || prec == namedImport) i"imported$qualifier by ${whereFound.tree}"
        else i"defined$qualifier in ${whereFound.owner}"

      /** Check that any previously found result from an inner context
       *  does properly shadow the new one from an outer context.
       */
      def checkNewOrShadowed(found: Type, newPrec: Int): Type =
        if (!previous.exists || (previous == found)) found
        else {
          if (!previous.isError && !found.isError)
            ctx.error(
              i"""reference to $name is ambiguous;
                 |it is both ${bindingString(newPrec, ctx, "")}
                 |and ${bindingString(prevPrec, prevCtx, " subsequently")}""".stripMargin,
              tree.pos)
          previous
        }

      /** The type representing a named import with enclosing name when imported
       *  from given `site` and `selectors`.
       */
      def namedImportRef(site: Type, selectors: List[untpd.Tree]): Type = {
        def checkUnambiguous(found: Type) = {
          val other = namedImportRef(site, selectors.tail)
          if (other.exists && (found != other))
            ctx.error(i"""reference to $name is ambiguous; it is imported twice in
                         |${ctx.tree}""".stripMargin,
                      tree.pos)
          found
        }
        selectors match {
          case Pair(Ident(from), Ident(`name`)) :: rest =>
            checkUnambiguous(selectionType(site, name, tree.pos))
          case Ident(`name`) :: rest =>
            checkUnambiguous(selectionType(site, name, tree.pos))
          case _ :: rest =>
            namedImportRef(site, rest)
          case nil =>
            NoType
        }
      }

      /** The type representing a wildcard import with enclosing name when imported
       *  from given import info
       */
      def wildImportRef(imp: ImportInfo): Type = {
        if (imp.wildcardImport && !(imp.excluded contains name.toTermName)) {
          val pre = imp.site
          if (!isDisabled(imp, pre)) {
            val denot = pre.member(name)
            if (denot.exists) return NamedType(pre, name).withDenot(denot)
          }
        }
        NoType
      }

      /** Is (some alternative of) the given predenotation `denot`
       *  defined in current compilation unit?
       */
      def isDefinedInCurrentUnit(denot: PreDenotation): Boolean = denot match {
        case DenotUnion(d1, d2) => isDefinedInCurrentUnit(d1) || isDefinedInCurrentUnit(d2)
        case denot: SingleDenotation => denot.symbol.sourceFile == ctx.source
      }

      // begin findRef
      if (ctx.scope == null) previous
      else {
        val outer = ctx.outer
        if ((ctx.scope ne outer.scope) || (ctx.owner ne outer.owner)) {
          val defDenots = ctx.denotsNamed(name)
          if (defDenots.exists) {
            val curOwner = ctx.owner
            val pre = curOwner.thisType
            val found = NamedType(pre, name).withDenot(defDenots toDenot pre)
            if (!(curOwner is Package) || isDefinedInCurrentUnit(defDenots))
              return checkNewOrShadowed(found, definition) // no need to go further out, we found highest prec entry
            else if (prevPrec < packageClause)
              return findRef(found, packageClause, ctx)(outer)
          }
        }
        val curImport = ctx.importInfo
        if (prevPrec < namedImport && (curImport ne outer.importInfo)) {
          val namedImp = namedImportRef(curImport.site, curImport.selectors)
          if (namedImp.exists)
            return findRef(checkNewOrShadowed(namedImp, namedImport), namedImport, ctx)(outer)
          if (prevPrec < wildImport) {
            val wildImp = wildImportRef(curImport)
            if (wildImp.exists)
              return findRef(checkNewOrShadowed(wildImp, wildImport), wildImport, ctx)(outer)
          }
        }
        findRef(previous, prevPrec, prevCtx)(outer)
      }
    }

    // begin typedIdent
    def kind = if (name.isTermName) "term" else "type" // !!! DEBUG
    println(s"typed ident $kind $name in ${ctx.owner}")
    if (ctx.mode is Mode.Pattern) {
      if (name == nme.WILDCARD)
        return tree.withType(pt)
      if (isVarPattern(tree))
        return typed(untpd.Bind(name, untpd.Ident(nme.WILDCARD)).withPos(tree.pos), pt)
    }

    val saved = importedFromRoot
    importedFromRoot = Set()

    val rawType =
      try findRef(NoType, BindingPrec.nothingBound, NoContext)
      finally importedFromRoot = saved
    checkValue(rawType, pt, tree.pos)

    val ownType =
      if (rawType.exists)
        checkAccessible(rawType, superAccess = false, tree.pos)
      else {
        ctx.error(i"not found: $name", tree.pos)
        ErrorType
      }
    tree.withType(ownType.underlyingIfRepeated)
  }

  def typedSelect(tree: untpd.Select, pt: Type)(implicit ctx: Context): Tree = {
    val qual1 = typedExpr(tree.qualifier, new SelectionProto(tree.name, pt))
    val ownType = checkedSelectionType(qual1, tree)
    checkValue(ownType, pt, tree.pos)
    cpy.Select(tree, qual1, tree.name).withType(ownType)
  }

  def typedThis(tree: untpd.This)(implicit ctx: Context): Tree = {
    val cls = qualifyingClass(tree, tree.qual, packageOK = false)
    tree.withType(cls.thisType)
  }

  def typedSuper(tree: untpd.Super)(implicit ctx: Context): Tree = {
    val mix = tree.mix
    val qual1 = typed(tree.qual)
    val cls = qual1.tpe.typeSymbol

    def findMixinSuper(site: Type): Type = site.parents filter (_.name == mix) match {
      case p :: Nil =>
        p
      case Nil =>
        errorType(i"$mix does not name a parent class of $cls", tree.pos)
      case p :: q :: _ =>
        errorType(s"ambiguous parent class qualifier", tree.pos)
    }
    val owntype =
      if (!mix.isEmpty) findMixinSuper(cls.info)
      else if (ctx.mode is Mode.InSuperInit) cls.info.firstParent
      else cls.info.parents.reduceLeft((x: Type, y: Type) => AndType(x, y))

    cpy.Super(tree, qual1, mix).withType(SuperType(cls.thisType, owntype))
  }

  def typedLiteral(tree: untpd.Literal)(implicit ctx: Context) =
    tree.withType(if (tree.const.tag == UnitTag) defn.UnitType else ConstantType(tree.const))

  def typedNew(tree: untpd.New, pt: Type)(implicit ctx: Context) = tree.tpt match {
    case templ: Template =>
      import untpd._
      val x = tpnme.ANON_CLASS
      val clsDef = TypeDef(Modifiers(Final), x, templ)
      typed(cpy.Block(tree, clsDef :: Nil, New(Ident(x), Nil)), pt)
    case _ =>
      val tpt1 = typedType(tree.tpt)
      val cls = checkClassTypeWithStablePrefix(tpt1.tpe, tpt1.pos)
      // todo in a later phase: checkInstantiatable(cls, tpt1.pos)
      cpy.New(tree, tpt1).withType(tpt1.tpe)
  }

  def typedPair(tree: untpd.Pair, pt: Type)(implicit ctx: Context) = {
    val (leftProto, rightProto) = pt.typeArgs match {
      case l :: r :: Nil if pt.typeSymbol == defn.PairClass => (l, r)
      case _ => (WildcardType, WildcardType)
    }
    val left1 = typed(tree.left, leftProto)
    val right1 = typed(tree.right, rightProto)
    cpy.Pair(tree, left1, right1).withType(defn.PairType.appliedTo(left1.tpe :: right1.tpe :: Nil))
  }

  def typedTyped(tree: untpd.Typed, pt: Type)(implicit ctx: Context): Tree = tree.expr match {
    case id: Ident if (ctx.mode is Mode.Pattern) && isVarPattern(id) && id.name != nme.WILDCARD =>
      import untpd._
      typed(Bind(id.name, Typed(Ident(nme.WILDCARD), tree.tpt)).withPos(id.pos))
    case _ =>
      val tpt1 = typedType(tree.tpt)
      val expr1 = typedExpr(tree.expr, tpt1.tpe)
      cpy.Typed(tree, tpt1, expr1).withType(tpt1.tpe)
  }

  def typedNamedArg(tree: untpd.NamedArg, pt: Type)(implicit ctx: Context) = {
    val arg1 = typed(tree.arg, pt)
    cpy.NamedArg(tree, tree.name, arg1).withType(arg1.tpe)
  }

  def typedAssign(tree: untpd.Assign, pt: Type)(implicit ctx: Context) = tree.lhs match {
    case lhs @ Apply(fn, args) =>
      typed(cpy.Apply(lhs, untpd.Select(fn, nme.update), args :+ tree.rhs), pt)
    case lhs =>
      val lhs1 = typed(lhs)
      def reassignmentToVal =
        errorTree(cpy.Assign(tree, lhs1, typed(tree.rhs, lhs1.tpe.widen)),
          "reassignment to val")
      lhs1.tpe match {
        case ref: TermRef if ref.symbol is Mutable =>
          cpy.Assign(tree, lhs1, typed(tree.rhs, ref.info)).withType(defn.UnitType)
        case ref: TermRef if ref.info.isParameterless =>
          val pre = ref.prefix
          val setterName = ref.name.setterName
          val setter = pre.member(setterName)
          lhs1 match {
            case lhs1: RefTree if setter.exists =>
              val setterTypeRaw = TermRef(pre, setterName).withDenot(setter)
              val setterType = checkAccessible(setterTypeRaw, isSuperSelection(tree), tree.pos)
              val lhs2 = lhs1.withName(setterName).withType(setterType)
              typed(cpy.Apply(tree, untpd.TypedSplice(lhs2), tree.rhs :: Nil))
            case _ =>
              reassignmentToVal
          }
        case _ =>
          reassignmentToVal
      }
  }

  def typedBlock(tree: untpd.Block, pt: Type)(implicit ctx: Context) = {
    val exprCtx = enterSyms(tree.stats)
    val stats1 = typedStats(tree.stats, ctx.owner)
    val expr1 = typedExpr(tree.expr, pt)(exprCtx)
    val result = cpy.Block(tree, stats1, expr1).withType(blockType(stats1, expr1.tpe))
    val leaks = CheckTrees.escapingRefs(result)
    if (leaks.isEmpty) result
    else if (isFullyDefined(pt)) {
      val expr2 = typed(untpd.Typed(untpd.TypedSplice(expr1), untpd.TypeTree(pt)))
      untpd.Block(stats1, expr2) withType expr2.tpe
    } else errorTree(result,
      i"local definition of ${leaks.head.name} escapes as part of block's type ${result.tpe}")
  }

  def typedIf(tree: untpd.If, pt: Type)(implicit ctx: Context) = {
    val cond1 = typed(tree.cond, defn.BooleanType)
    val thenp1 = typed(tree.thenp, pt)
    val elsep1 = typed(if (tree.elsep.isEmpty) unitLiteral else tree.elsep, pt)
    cpy.If(tree, cond1, thenp1, elsep1).withType(thenp1.tpe | elsep1.tpe)
  }

  def typedFunction(tree: untpd.Function, pt: Type)(implicit ctx: Context) = {
    val untpd.Function(args, body) = tree
    if (ctx.mode is Mode.Type)
      typed(cpy.AppliedTypeTree(tree,
        ref(defn.FunctionClass(args.length).typeConstructor), args :+ body), pt)
    else {
      val params = args.asInstanceOf[List[ValDef]]
      val protoFormals: List[Type] = pt match {
        case _ if pt.typeSymbol == defn.FunctionClass(params.length) =>
          pt.typeArgs take params.length
        case SAMType(meth) =>
          val MethodType(_, paramTypes) = meth.info
          paramTypes
        case _ =>
          params map Function.const(WildcardType)
      }
      val inferredParams: List[untpd.ValDef] =
        for ((param, formal) <- params zip protoFormals) yield
          if (!param.tpt.isEmpty) param
          else {
            val paramType =
              if (isFullyDefined(formal)) formal
              else errorType("missing parameter type", param.pos)
            cpy.ValDef(param, param.mods, param.name, untpd.TypeTree(paramType), param.rhs)
          }
      typed(desugar.makeClosure(inferredParams, body), pt)
    }
  }

  def typedClosure(tree: untpd.Closure, pt: Type)(implicit ctx: Context) = {
    val env1 = tree.env mapconserve (typed(_))
    val meth1 = typed(tree.meth)
    val ownType = meth1.tpe.widen match {
      case mt: MethodType if !mt.isDependent =>
        mt.toFunctionType
      case mt: MethodType =>
        errorType(i"internal error: cannot turn dependent method type $mt into closure", tree.pos)
      case tp =>
        errorType(i"internal error: closing over non-method $tp", tree.pos)
    }
    cpy.Closure(tree, env1, meth1, EmptyTree).withType(ownType)
  }

  def typedMatch(tree: untpd.Match, pt: Type)(implicit ctx: Context) = tree.selector match {
    case EmptyTree =>
      typed(desugar.makeCaseLambda(tree.cases) withPos tree.pos, pt)
    case _ =>
      val sel1 = typedExpr(tree.selector)
      val selType =
        if (isFullyDefined(sel1.tpe)) sel1.tpe
        else errorType("internal error: type of pattern selector is not fully defined", tree.pos)

      /** gadtSyms = "all type parameters of enclosing methods that appear
       *              non-variantly in the selector type
       */
      val gadtSyms: Set[Symbol] = {
        val accu = new TypeAccumulator[Set[Symbol]] {
          def apply(tsyms: Set[Symbol], t: Type): Set[Symbol] = {
            val tsyms1 = t match {
              case tr: TypeRef if (tr.symbol is TypeParam) && tr.symbol.owner.isTerm && variance == 0 =>
                tsyms + tr.symbol
              case _ =>
                tsyms
            }
            foldOver(tsyms1, t)
          }
        }
        accu(Set.empty, selType)
    }

    def typedCase(tree: untpd.CaseDef): CaseDef = {
      def caseRest(pat: Tree)(implicit ctx: Context) = {
        gadtSyms foreach (_.resetGADTFlexType)
        foreachSubTreeOf(pat) {
          case b: Bind =>
            if (ctx.scope.lookup(b.name) == NoSymbol) ctx.enter(b.symbol)
            else ctx.error(i"duplicate pattern variable: ${b.name}", b.pos)
          case _ =>
        }
        val guard1 = typedExpr(tree.guard, defn.BooleanType)
        val body1 = typedExpr(tree.body, pt)
        cpy.CaseDef(tree, pat, guard1, body1) withType body1.tpe
      }
      val doCase: () => CaseDef =
        () => caseRest(typedPattern(tree.pat, selType))(ctx.fresh.withNewScope)
      (doCase /: gadtSyms) ((op, tsym) => tsym.withGADTFlexType(op)) ()
    }

    val cases1 = tree.cases mapconserve typedCase
    cpy.Match(tree, sel1, cases1).withType(ctx.lub(cases1.tpes))
  }

  def typedReturn(tree: untpd.Return)(implicit ctx: Context): Return = {
    def enclMethInfo(cx: Context): (Tree, Type) =
      if (cx == NoContext || cx.tree.isInstanceOf[Trees.TypeDef[_]]) {
        ctx.error("return outside method definition")
        (EmptyTree, WildcardType)
      }
      else cx.tree match {
        case ddef: DefDef =>
          val meth = ddef.symbol
          val from = Ident(TermRef.withSym(NoPrefix, meth.asTerm))
          val proto =
            if (meth.isConstructor)
              defn.UnitType
            else if (ddef.tpt.isEmpty)
              errorType(i"method $meth has return statement; needs result type", tree.pos)
            else
              ddef.tpt.tpe
          (from, proto)
        case _ =>
          enclMethInfo(cx.outer)
      }
    val (from, proto) = enclMethInfo(ctx)
    val expr1 = typedExpr(if (tree.expr.isEmpty) untpd.unitLiteral else tree.expr, proto)
    cpy.Return(tree, expr1, from) withType defn.NothingType
  }

  def typedTry(tree: untpd.Try, pt: Type)(implicit ctx: Context): Try = {
    val expr1 = typed(tree.expr, pt)
    val handler1 = typed(tree.handler, defn.FunctionType(defn.ThrowableType :: Nil, pt))
    val finalizer1 = typed(tree.finalizer, defn.UnitType)
    val handlerResultType = handler1.tpe match {
      case defn.FunctionType(_, resultType) => resultType
      case _ => defn.NothingType
    }
    cpy.Try(tree, expr1, handler1, finalizer1).withType(expr1.tpe | handlerResultType)
  }

  def typedThrow(tree: untpd.Throw)(implicit ctx: Context): Throw = {
    val expr1 = typed(tree.expr, defn.ThrowableType)
    cpy.Throw(tree, expr1) withType defn.NothingType
  }

  def typedSeqLiteral(tree: untpd.SeqLiteral, pt: Type)(implicit ctx: Context): SeqLiteral = {
    val proto1 = pt.elemType orElse WildcardType
    val elems1 = tree.elems mapconserve (typed(_, proto1))
    cpy.SeqLiteral(tree, elems1) withType ctx.lub(elems1.tpes)
  }

  def typedTypeTree(tree: untpd.TypeTree, pt: Type)(implicit ctx: Context): TypeTree = {
    val (original1, ownType) = tree.original match {
      case untpd.EmptyTree =>
        assert(isFullyDefined(pt))
        (EmptyTree, pt)
      case original: ValDef =>
        val meth = symbolOfTree(original)
        assert(meth.exists, meth)
        (EmptyTree, meth.info)
      case original =>
        val original1 = typed(original)
        (original1, original1.tpe)
    }
    cpy.TypeTree(tree, original1) withType ownType
  }

  def typedSingletonTypeTree(tree: untpd.SingletonTypeTree)(implicit ctx: Context): SingletonTypeTree = {
    val ref1 = typedExpr(tree.ref)
    checkStable(ref1.qualifierType, tree.pos)
    cpy.SingletonTypeTree(tree, ref1) withType ref1.tpe
  }

  def typedSelectFromTypeTree(tree: untpd.SelectFromTypeTree, pt: Type)(implicit ctx: Context): SelectFromTypeTree = {
    val qual1 = typedType(tree.qualifier, new SelectionProto(tree.name, pt))
    cpy.SelectFromTypeTree(tree, qual1, tree.name).withType(checkedSelectionType(qual1, tree))
  }

  def typedAndTypeTree(tree: untpd.AndTypeTree)(implicit ctx: Context): AndTypeTree = {
    val left1 = typed(tree.left)
    val right1 = typed(tree.right)
    cpy.AndTypeTree(tree, left1, right1) withType left1.tpe & right1.tpe
  }

  def typedOrTypeTree(tree: untpd.OrTypeTree)(implicit ctx: Context): OrTypeTree = {
    val left1 = typed(tree.left)
    val right1 = typed(tree.right)
    cpy.OrTypeTree(tree, left1, right1) withType left1.tpe | right1.tpe
  }

  def typedRefinedTypeTree(tree: untpd.RefinedTypeTree)(implicit ctx: Context): RefinedTypeTree = {
    val tpt1 = typedAheadType(tree.tpt)
    val refineClsDef = desugar.refinedTypeToClass(tree)
    val throwAwayScopeCtx = ctx.fresh.withNewScope
    val refineCls = createSymbol(refineClsDef)(throwAwayScopeCtx).asClass
    val TypeDef(_, _, Template(_, _, _, refinements1)) = typed(refineClsDef)
    assert(tree.refinements.length == refinements1.length, s"${tree.refinements} != $refinements1")
    def addRefinement(parent: Type, refinement: Tree): Type = {
      foreachSubTreeOf(refinement) {
        case tree: RefTree =>
          if (tree.symbol.owner == refineCls && tree.pos.start <= tree.symbol.pos.end)
            ctx.error("illegal forward reference in refinement", tree.pos)
        case _ =>
      }
      val rsym = refinement.symbol
      val rinfo = if (rsym is Accessor) rsym.info.resultType else rsym.info
      RefinedType(parent, rsym.name, rt => rinfo.substThis(refineCls, RefinedThis(rt)))
    }
    cpy.RefinedTypeTree(tree, tpt1, refinements1) withType
      (tpt1.tpe /: refinements1)(addRefinement)
  }

  def typedAppliedTypeTree(tree: untpd.AppliedTypeTree)(implicit ctx: Context): AppliedTypeTree = {
    val tpt1 = typed(tree.tpt)
    val args1 = tree.args mapconserve (typed(_))
    val tparams = tpt1.tpe.typeParams
    if (args1.length != tparams.length)
      ctx.error(i"wrong number of type arguments for ${tpt1.tpe}, should be ${tparams.length}")
    // todo in later phase: check arguments conform to parameter bounds
    cpy.AppliedTypeTree(tree, tpt1, args1) withType tpt1.tpe.appliedTo(args1.tpes)
  }

  def typedTypeBoundsTree(tree: untpd.TypeBoundsTree)(implicit ctx: Context): TypeBoundsTree = {
    val lo1 = typed(tree.lo)
    val hi1 = typed(tree.hi)
    if (!(lo1.tpe <:< hi1.tpe))
      ctx.error(i"lower bound ${lo1.tpe} does not conform to upper bound ${hi1.tpe}", tree.pos)
    cpy.TypeBoundsTree(tree, lo1, hi1) withType TypeBounds(lo1.tpe, hi1.tpe)
  }

  def typedBind(tree: untpd.Bind, pt: Type)(implicit ctx: Context): Bind = {
    val body1 = typed(tree.body, pt)
    val sym = ctx.newSymbol(ctx.owner, tree.name.asTermName, EmptyFlags, pt, coord = tree.pos)
    cpy.Bind(tree, tree.name, body1) withType TermRef.withSym(NoPrefix, sym)
  }

  def typedAlternative(tree: untpd.Alternative, pt: Type)(implicit ctx: Context): Alternative = {
    val trees1 = tree.trees mapconserve (typed(_, pt))
    cpy.Alternative(tree, trees1) withType ctx.lub(trees1.tpes)
  }

  def typedModifiers(mods: untpd.Modifiers)(implicit ctx: Context): Modifiers = {
    val annotations1 = mods.annotations mapconserve typedAnnotation
    if (annotations1 eq mods.annotations) mods.asInstanceOf[Modifiers]
    else Modifiers(mods.flags, mods.privateWithin, annotations1)
  }

  def typedAnnotation(annot: untpd.Tree)(implicit ctx: Context): Tree =
    typed(annot, defn.AnnotationClass.typeConstructor)

  def typedValDef(vdef: untpd.ValDef, sym: Symbol)(implicit ctx: Context) = {
    val ValDef(mods, name, tpt, rhs) = vdef
    val mods1 = typedModifiers(mods)
    val tpt1 = typedType(tpt)
    val rhs1 = typedExpr(rhs, tpt1.tpe)
    val refType = if (sym.exists) sym.symRef else NoType
    cpy.ValDef(vdef, mods1, name, tpt1, rhs1).withType(refType)
  }

  def typedDefDef(ddef: untpd.DefDef, sym: Symbol)(implicit ctx: Context) = {
    val DefDef(mods, name, tparams, vparamss, tpt, rhs) = ddef
    val mods1 = typedModifiers(mods)
    val tparams1 = tparams mapconserve (typed(_).asInstanceOf[TypeDef])
    val vparamss1 = vparamss mapconserve(_ mapconserve (typed(_).asInstanceOf[ValDef]))
    val tpt1 = typedType(tpt)
    val rhs1 = typedExpr(rhs, tpt1.tpe)
    cpy.DefDef(ddef, mods1, name, tparams1, vparamss1, tpt1, rhs1).withType(sym.symRef)
    //todo: make sure dependent method types do not depend on implicits or by-name params
  }

  def typedTypeDef(tdef: untpd.TypeDef, sym: Symbol)(implicit ctx: Context): TypeDef = {
    val TypeDef(mods, name, rhs) = tdef
    val mods1 = typedModifiers(mods)
    val rhs1 = typedType(rhs)
    cpy.TypeDef(tdef, mods1, name, rhs1).withType(sym.symRef)
  }

  def typedClassDef(cdef: untpd.TypeDef, cls: ClassSymbol)(implicit ctx: Context) = {
    val TypeDef(mods, name, impl @ Template(constr, parents, self, body)) = cdef
    val mods1 = typedModifiers(mods)
    val constr1 = typed(constr).asInstanceOf[DefDef]
    val parents1 = parents mapconserve (typed(_))
    val self1 = cpy.ValDef(self, typedModifiers(self.mods), self.name, typedType(self.tpt), EmptyTree)
      .withType(NoType)

    val localDummy = ctx.newLocalDummy(cls, impl.pos)
    val body1 = typedStats(body, localDummy)(inClassContext(cls, self.name))
    val impl1 = cpy.Template(impl, constr1, parents1, self1, body1)
      .withType(localDummy.symRef)

    cpy.TypeDef(cdef, mods1, name, impl1).withType(cls.symRef)

    // todo later: check that
    //  1. If class is non-abstract, it is instantiatable:
    //  - self type is s supertype of own type
    //  - all type members have consistent bounds
    // 2. all private type members have consistent bounds
    // 3. Types do not override classes.
    // 4. Polymorphic type defs override nothing.
  }

  def typedImport(imp: untpd.Import, sym: Symbol)(implicit ctx: Context): Import = {
    val expr1 = typedExpr(imp.expr, AnySelectionProto)
    cpy.Import(imp, expr1, imp.selectors).withType(sym.symRef)
  }

  def typedAnnotated(tree: untpd.Annotated, pt: Type)(implicit ctx: Context): Tree = {
    val annot1 = typed(tree.annot, defn.AnnotationClass.typeConstructor)
    val arg1 = typed(tree.arg, pt)
    val ownType = AnnotatedType(Annotation(annot1), arg1.tpe)
    if (ctx.mode is Mode.Type)
      cpy.Annotated(tree, annot1, arg1) withType ownType
    else
      cpy.Typed(tree, arg1, TypeTree(ownType)) withType ownType
  }

 def typedPackageDef(tree: untpd.PackageDef)(implicit ctx: Context): Tree = {
    val pid1 = typedExpr(tree.pid, AnySelectionProto)
    val pkg = pid1.symbol
    val packageContext =
      if (pkg is Package) ctx.fresh withOwner pkg.moduleClass
      else {
        ctx.error(i"$pkg is not a packge", tree.pos)
        ctx
      }
    val stats1 = typedStats(tree.stats, NoSymbol)(packageContext)
    cpy.PackageDef(tree, pid1.asInstanceOf[RefTree], stats1) withType pkg.symRef
  }

  def typedExpanded(tree: untpd.Tree, pt: Type = WildcardType)(implicit ctx: Context): Tree = {
    val sym = symOfTree.remove(tree).getOrElse(NoSymbol)
    sym.ensureCompleted()
    def localContext = ctx.fresh.withOwner(sym)
    typedTree remove tree match {
      case Some(tree1) => tree1
      case none => tree match {
        case tree: untpd.Ident => typedIdent(tree, pt)
        case tree: untpd.Select => typedSelect(tree, pt)
        case tree: untpd.This => typedThis(tree)
        case tree: untpd.Super => typedSuper(tree)
        case tree: untpd.Apply => typedApply(tree, pt)
        case tree: untpd.TypeApply => typedTypeApply(tree, pt)
        case tree: untpd.Literal => typedLiteral(tree)
        case tree: untpd.New => typedNew(tree, pt)
        case tree: untpd.Pair => typedPair(tree, pt)
        case tree: untpd.Typed => typedTyped(tree, pt)
        case tree: untpd.NamedArg => typedNamedArg(tree, pt)
        case tree: untpd.Assign => typedAssign(tree, pt)
        case tree: untpd.Block => typedBlock(tree, pt)
        case tree: untpd.If => typedIf(tree, pt)
        case tree: untpd.Function => typedFunction(tree, pt)
        case tree: untpd.Closure => typedClosure(tree, pt)
        case tree: untpd.Match => typedMatch(tree, pt)
        case tree: untpd.Return => typedReturn(tree)
        case tree: untpd.Try => typedTry(tree, pt)
        case tree: untpd.Throw => typedThrow(tree)
        case tree: untpd.SeqLiteral => typedSeqLiteral(tree, pt)
        case tree: untpd.TypeTree => typedTypeTree(tree, pt)
        case tree: untpd.SingletonTypeTree => typedSingletonTypeTree(tree)
        case tree: untpd.SelectFromTypeTree => typedSelectFromTypeTree(tree, pt)
        case tree: untpd.AndTypeTree => typedAndTypeTree(tree)
        case tree: untpd.OrTypeTree => typedOrTypeTree(tree)
        case tree: untpd.RefinedTypeTree => typedRefinedTypeTree(tree)
        case tree: untpd.AppliedTypeTree => typedAppliedTypeTree(tree)
        case tree: untpd.TypeBoundsTree => typedTypeBoundsTree(tree)
        case tree: untpd.Bind => typedBind(tree, pt)
        case tree: untpd.Alternative => typedAlternative(tree, pt)
        case tree: untpd.ValDef =>
          typedValDef(tree, sym)(localContext)
        case tree: untpd.DefDef =>
          val typer1 = nestedTyper.remove(sym).get
          typer1.typedDefDef(tree, sym)(localContext.withTyper(typer1))
        case tree: untpd.TypeDef =>
          if (tree.isClassDef) typedClassDef(tree, sym.asClass)(localContext)
          else typedTypeDef(tree, sym)(localContext.withNewScope)
        case tree: untpd.Import => typedImport(tree, sym)
        case tree: untpd.PackageDef => typedPackageDef(tree)
        case tree: untpd.Annotated => typedAnnotated(tree, pt)
        case tree: untpd.TypedSplice => tree.tree
        case untpd.EmptyTree => tpd.EmptyTree
        case _ => typed(desugar(tree), pt)
      }
    }
  }

  def typed(tree: untpd.Tree, pt: Type = WildcardType)(implicit ctx: Context): Tree = {

    def encodeName(tree: untpd.Tree) = tree match {
      case tree: NameTree => tree.withName(tree.name.encode)
      case _ => tree
    }

    val tree1 = typedExpanded(encodeName(tree), pt)
    ctx.interpolateUndetVars(tree1.tpe.widen, tree1.pos)
    adapt(tree1, pt)
  }

  def typedTrees(trees: List[untpd.Tree])(implicit ctx: Context): List[Tree] =
    trees mapconserve (typed(_))

  def typedStats(stats: List[untpd.Tree], exprOwner: Symbol)(implicit ctx: Context): List[tpd.Tree] = {
    val buf = new mutable.ListBuffer[Tree]
    @tailrec def traverse(stats: List[untpd.Tree])(implicit ctx: Context): List[Tree] = stats match {
      case (imp: untpd.Import) :: rest =>
        val imp1 = typed(imp)
        buf += imp1
        traverse(rest)(importContext(imp1.symbol, imp.selectors))
      case (mdef: untpd.MemberDef) :: rest =>
        expandedTree remove mdef match {
          case Some(xtree) =>
            traverse(xtree :: rest)
          case none =>
            buf += typed(mdef)
            traverse(rest)
        }
      case Thicket(stats) :: rest =>
        traverse(stats ++ rest)
      case stat :: rest =>
        val nestedCtx = if (exprOwner == ctx.owner) ctx else ctx.fresh.withOwner(exprOwner)
        buf += typed(stat)(nestedCtx)
        traverse(rest)
      case _ =>
        buf.toList
    }
    traverse(stats)
  }

  def typedExpr(tree: untpd.Tree, pt: Type = WildcardType)(implicit ctx: Context): Tree =
    typed(tree, pt)(ctx retractMode Mode.PatternOrType)
  def typedType(tree: untpd.Tree, pt: Type = WildcardType)(implicit ctx: Context): Tree =
    typed(tree, pt)(ctx addMode Mode.Type)
  def typedPattern(tree: untpd.Tree, pt: Type = WildcardType)(implicit ctx: Context): Tree =
    typed(tree, pt)(ctx addMode Mode.Pattern)

  def tryEither[T](op: Context => T)(fallBack: StateFul[T] => T)(implicit ctx: Context) = {
    val nestedCtx = ctx.fresh.withNewTyperState
    val result = op(nestedCtx)
    if (nestedCtx.reporter.hasErrors)
      fallBack(StateFul(result, nestedCtx.typerState))
    else {
      nestedCtx.typerState.commit()
      result
    }
  }

  def tryInsertApply(tree: Tree, pt: Type)(fallBack: StateFul[Tree] => Tree)(implicit ctx: Context): Tree =
    tryEither {
      implicit ctx => typedSelect(untpd.Select(untpd.TypedSplice(tree), nme.apply), pt)
    } {
      fallBack
    }

  /** (-1) For expressions with annotated types, let AnnotationCheckers decide what to do
   *  (0) Convert expressions with constant types to literals (unless in interactive/scaladoc mode)
   */

  /** Perform the following adaptations of expression, pattern or type `tree` wrt to
   *  given prototype `pt`:
   *  (1) Resolve overloading
   *  (2) Apply parameterless functions
   *  (3) Apply polymorphic types to fresh instances of their type parameters and
   *      store these instances in context.undetparams,
   *      unless followed by explicit type application.
   *  (4) Do the following to unapplied methods used as values:
   *  (4.1) If the method has only implicit parameters pass implicit arguments
   *  (4.2) otherwise, if `pt` is a function type and method is not a constructor,
   *        convert to function by eta-expansion,
   *  (4.3) otherwise, if the method is nullary with a result type compatible to `pt`
   *        and it is not a constructor, apply it to ()
   *  otherwise issue an error
   *  (5) Convert constructors in a pattern as follows:
   *  (5.1) If constructor refers to a case class factory, set tree's type to the unique
   *        instance of its primary constructor that is a subtype of the expected type.
   *  (5.2) If constructor refers to an extractor, convert to application of
   *        unapply or unapplySeq method.
   *
   *  (6) Convert all other types to TypeTree nodes.
   *  (7) When in TYPEmode but not FUNmode or HKmode, check that types are fully parameterized
   *      (7.1) In HKmode, higher-kinded types are allowed, but they must have the expected kind-arity
   *  (8) When in both EXPRmode and FUNmode, add apply method calls to values of object type.
   *  (9) If there are undetermined type variables and not POLYmode, infer expression instance
   *  Then, if tree's type is not a subtype of expected type, try the following adaptations:
   *  (10) If the expected type is Byte, Short or Char, and the expression
   *      is an integer fitting in the range of that type, convert it to that type.
   *  (11) Widen numeric literals to their expected type, if necessary
   *  (12) When in mode EXPRmode, convert E to { E; () } if expected type is scala.Unit.
   *  (13) When in mode EXPRmode, apply AnnotationChecker conversion if expected type is annotated.
   *  (14) When in mode EXPRmode, apply a view
   *  If all this fails, error
   */
  def adapt(tree: Tree, pt: Type)(implicit ctx: Context): Tree = {

    def adaptOverloaded(ref: TermRef) = {
      val altDenots = ref.denot.alternatives
      val alts = altDenots map (alt =>
        TermRef.withSym(ref.prefix, alt.symbol.asTerm))
      def expectedStr = err.expectedTypeStr(pt)
      resolveOverloaded(alts, pt) match {
        case alt :: Nil =>
          adapt(tree.withType(alt), pt)
        case Nil =>
          def noMatches =
            errorTree(tree,
              i"""none of the ${err.overloadedAltsStr(altDenots)}
                 |match $expectedStr""".stripMargin)
          pt match {
            case pt: FunProtoType => tryInsertApply(tree, pt)(_ => noMatches)
            case _ => noMatches
          }
        case alts =>
          errorTree(tree,
            i"""Ambiguous overload. The ${err.overloadedAltsStr(altDenots take 2)}
               |both match $expectedStr""".stripMargin)
      }
    }

    def adaptToArgs(tp: Type, pt: FunProtoType) = tp match {
      case _: MethodType => tree
      case _ => tryInsertApply(tree, pt) {
        def fn = err.refStr(methPart(tree).tpe)
        val more = tree match {
          case Apply(_, _) => " more"
          case _ => ""
        }
        _ => errorTree(tree, i"$fn does not take$more parameters")
      }
    }

    def adaptNoArgs(tp: Type) = tp match {
      case tp: ExprType =>
        adapt(tree.withType(tp.resultType), pt)
      case tp: ImplicitMethodType =>
        val args = tp.paramTypes map (inferImplicit(_, EmptyTree, tree.pos))
        adapt(tpd.Apply(tree, args), pt)
      case tp: MethodType =>
        if (defn.isFunctionType(pt) && !tree.symbol.isConstructor)
          etaExpand(tree, tp)
        else if (tp.paramTypes.isEmpty)
          adapt(tpd.Apply(tree, Nil), pt)
        else
          errorTree(tree,
            i"""missing arguments for ${tree.symbol}
               |follow this method with `_' if you want to treat it as a partially applied function""".stripMargin)
      case _ =>
        if (tp <:< pt) tree
        else if (ctx.mode is Mode.Pattern) tree // no subtype check for patterns
        else if (ctx.mode is Mode.Type) err.typeMismatch(tree, pt)
        else adaptToSubType(tp)
    }

    def adaptToSubType(tp: Type): Tree = {
      // try converting a constant to the target type
      val folded = ConstFold(tree, pt)
      if (folded ne EmptyTree) return folded
      // drop type if prototype is Unit
      if (pt.typeSymbol == defn.UnitClass)
        return tpd.Block(tree :: Nil, Literal(Constant()))
      // convert function literal to SAM closure
      tree match {
        case Closure(Nil, id @ Ident(nme.ANON_FUN), _)
        if defn.isFunctionType(tree.tpe) && !defn.isFunctionType(pt) =>
          pt match {
            case SAMType(meth)
            if tree.tpe <:< meth.info.toFunctionType && isFullyDefined(pt, forceIt = false) =>
              return cpy.Closure(tree, Nil, id, TypeTree(pt)).withType(pt)
            case _ =>
          }
        case _ =>
      }
      // try an implicit conversion
      val adapted = inferView(tree, pt)
      if (adapted ne EmptyTree) return adapted
      // if everything fails issue a type error
      err.typeMismatch(tree, pt)
    }

    tree.tpe.widen match {
      case ref: TermRef =>
        adaptOverloaded(ref)
      case poly: PolyType =>
        if (pt.isInstanceOf[PolyProtoType]) tree
        else {
          val tracked = ctx.track(poly)
          val tvars = ctx.newTypeVars(tracked, tree.pos)
          adapt(tpd.TypeApply(tree, tvars map (tpd.TypeTree(_))), pt)
        }
      case tp =>
        pt match {
          case pt: FunProtoType => adaptToArgs(tp, pt)
          case _ => adaptNoArgs(tp)
        }
    }
  }
}