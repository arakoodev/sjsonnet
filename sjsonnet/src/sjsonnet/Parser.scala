package sjsonnet

import fastparse.WhitespaceApi

object Parser{
  val precedenceTable = Seq(
    Seq("*", "/", "%"),
    Seq("+", "-"),
    Seq("<<", ">>"),
    Seq("<", ">", "<=", ">=", "in"),
    Seq("==", "!="),
    Seq("&"),
    Seq("^"),
    Seq("|"),
    Seq("&&"),
    Seq("||"),
  )
  val precedence = precedenceTable
    .reverse
    .zipWithIndex
    .flatMap{case (ops, idx) => ops.map(_ -> idx)}
    .toMap

  val White = WhitespaceApi.Wrapper {
    import fastparse.all._
    NoTrace(
      (
        CharsWhileIn(" \t\n", 1) |
        "/*" ~ (!"*/" ~ AnyChar).rep ~ "*/"  |
        "//" ~ CharsWhile(_ != '\n', 0) |
        "#" ~ CharsWhile(_ != '\n', 0)
      ).rep
    )
  }
  import fastparse.noApi._
  import White._

  val keywords = Set(
    "assert", "else", "error", "false", "for", "function", "if", "import", "importstr",
    "in", "local", "null", "tailstrict", "then", "self", "super", "true"
  )
  val id = P(
    CharIn("_" ++ ('a' to 'z') ++ ('A' to 'Z')) ~~
    CharsWhileIn("_" ++ ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'), min = 0)
  ).!.filter(s => !keywords.contains(s))

  val number: P[Expr.Num] = P(
    CharsWhileIn('0' to '9') ~~
    ("." ~ CharsWhileIn('0' to '9')).? ~~
    (("e" | "E") ~ ("+" | "-").? ~~ CharsWhileIn('0' to '9')).?
  ).!.map(s => Expr.Num(s.toDouble))

  val escape = P("\\" ~~ AnyChar.!).map{
    case "\"" => "\""
    case "'" => "\'"
    case "\\" => "\\"
    case "/" => "/"
    case "b" => "\b"
    case "f" => "\f"
    case "n" => "\n"
    case "r" => "\r"
    case "t" => "\t"
  }
  val string: P[String] = P(
    "\"".~/ ~~ (CharsWhile(x => x != '"' && x != '\\').! | escape).repX ~~ "\"" |
    "'".~/ ~~ (CharsWhile(x => x != '\'' && x != '\\').! | escape).repX ~~ "'" |
    "@\"".~/ ~~ (CharsWhile(_ != '"').! | "\"\"".!.map(_ => "\"")).repX ~~ "\"" |
    "@'".~/ ~~ (CharsWhile(_ != '\'').! | "''".!.map(_ => "'")).repX ~~ "'" |
    "|||".~/ ~~ CharsWhileIn(" \t", 0) ~~ "\n" ~~ tripleBarStringHead.flatMap { case (pre, w, head) =>
      tripleBarStringBody(w).map(pre ++ Seq(head, "\n") ++ _)
    } ~~ "\n" ~~ CharsWhileIn(" \t", min=0) ~~ "|||"
  ).map(_.mkString).opaque()

  val tripleBarStringHead = P(
    (CharsWhileIn(" \t", min=0) ~~ "\n".!).repX ~~
    CharsWhileIn(" \t", min=1).! ~~
    CharsWhile(_ != '\n').!
  )
  val tripleBarBlank = P( "\n" ~~ CharsWhileIn(" \t", min=0) ~~ &("\n").map(_ => "\n") )
  def tripleBarStringBody(w: String) = P(
    (tripleBarBlank | "\n" ~~ w ~~ CharsWhile(_ != '\n').!.map(_ + "\n")).repX
  )

  val `null` = P("null").map(_ => Expr.Null)
  val `true` = P("true").map(_ => Expr.True)
  val `false` = P("false").map(_ => Expr.False)
  val `self` = P("self").map(_ => Expr.Self)
  val $ = P("$").map(_ => Expr.$)
  val `super` = P("super").map(_ => Expr.Super)

  val obj: P[Expr] = P( "{" ~/ objinside.map(Expr.Obj) ~ "}" )
  val arr: P[Expr] = P(
    "[" ~/ ("]".!.map(_ => Expr.Arr(Nil)) | arrBody ~ "]")
  )
  val compSuffix = P( forspec ~ compspec ).map(Left(_))
  val arrBody: P[Expr] = P(
    expr ~ (compSuffix | "," ~/ (compSuffix | (expr.rep(0, sep = ",") ~ ",".?).map(Right(_)))).?
  ).map{
    case (first, None) => Expr.Arr(Seq(first))
    case (first, Some(Left(comp))) => Expr.Comp(first, comp._1, comp._2)
    case (first, Some(Right(rest))) => Expr.Arr(Seq(first) ++ rest)
  }
  val assertExpr: P[Expr] = P( assertStmt ~/ ";" ~ expr ).map(Expr.AssertExpr.tupled)
  val function: P[Expr] = P( "(" ~/ params ~ ")" ~ expr ).map(Expr.Function.tupled)
  val ifElse: P[Expr] = P( expr ~ "then" ~ expr ~ ("else" ~ expr).? ).map(Expr.IfElse.tupled)
  val localExpr: P[Expr] = P( bind.rep(min=1, sep = ","~/) ~ ";" ~ expr ).map(Expr.LocalExpr.tupled)

  val expr: P[Expr] = P("" ~ expr1 ~ (binaryop ~/ expr1).rep ~ "").map{ case (pre, fs) =>
    var remaining = fs
    def climb(minPrec: Int, current: Expr): Expr = {
      var result = current
      while(
        remaining.headOption match{
          case None => false
          case Some((op, next)) =>
            val prec: Int = precedence(op)
            if (prec < minPrec) false
            else{
              remaining = remaining.tail
              val rhs = climb(prec + 1, next)
              result = Expr.BinaryOp(result, op, rhs)
              true
            }
        }
      )()
      result
    }

    climb(0, pre)
  }

  val expr1: P[Expr] = P(expr2 ~ exprSuffix2.rep).map{
    case (pre, fs) => fs.foldLeft(pre){case (p, f) => f(p) }
  }

  val exprSuffix2: P[Expr => Expr] = P(
    ("." ~/ id).map(x => Expr.Select(_: Expr, x)) |
    ("[" ~/ expr.? ~ (":" ~ expr.?).rep ~ "]").map{
      case (Some(tree), Seq()) => Expr.Lookup(_: Expr, tree)
      case (start, ins) => Expr.Slice(_: Expr, start, ins.lift(0).flatten, ins.lift(1).flatten)
    } |
    ("(" ~/ args ~ ")").map(x => Expr.Apply(_: Expr, x)) |
    ("{" ~/ objinside ~ "}").map(x => Expr.ObjExtend(_: Expr, x))
  )

  // Any `expr` that isn't naively left-recursive
  val expr2 = P(
    `null` | `true` | `false` | `self` | $ | number |
    string.map(Expr.Str) | obj | arr | `super`
    | id.map(Expr.Id)
    | "local" ~/ localExpr
    | "(" ~/ expr.map(Expr.Parened) ~ ")"
    | "if" ~/ ifElse
    | "function" ~/ function
    | "import" ~/ string.map(Expr.Import)
    | "importstr" ~/ string.map(Expr.ImportStr)
    | "error" ~/ expr.map(Expr.Error)
    | assertExpr
    | (unaryop ~/ expr1).map(Expr.UnaryOp.tupled)
  )

  val objinside: P[Expr.ObjBody] = P(
    member.rep(sep = ",") ~ ",".? ~ (forspec ~ compspec).?
  ).map{
    case (exprs, None) => Expr.ObjBody.MemberList(exprs)
    case (exprs, Some(comps)) =>
      val preLocals = exprs.takeWhile(_.isInstanceOf[Expr.Member.BindStmt]).map(_.asInstanceOf[Expr.Member.BindStmt])
      val Expr.Member.Field(Expr.FieldName.Dyn(lhs), false, None, ":", rhs) =
        exprs(preLocals.length)
      val postLocals = exprs.drop(preLocals.length+1).takeWhile(_.isInstanceOf[Expr.Member.BindStmt])
        .map(_.asInstanceOf[Expr.Member.BindStmt])
      Expr.ObjBody.ObjComp(preLocals, lhs, rhs, postLocals, comps._1, comps._2)
  }

  val member: P[Expr.Member] = P( objlocal | assertStmt | field )
  val field = P(
    (fieldname ~/ "+".!.? ~ ("(" ~ params ~ ")").? ~ fieldKeySep ~ expr).map{
      case (name, plus, p, h2, e) =>
        Expr.Member.Field(name, plus.nonEmpty, p, h2, e)
    }
  )
  val fieldKeySep = P( ":::" | "::" | ":" ).!
  val objlocal = P( "local" ~/ bind ).map(Expr.Member.BindStmt)
  val compspec: P[Seq[Expr.CompSpec]] = P( (forspec | ifspec).rep )
  val forspec = P( "for" ~/ id ~ "in" ~ expr ).map(Expr.ForSpec.tupled)
  val ifspec = P( "if" ~/ expr ).map(Expr.IfSpec)
  val fieldname = P( id.map(Expr.FieldName.Fixed) | string.map(Expr.FieldName.Fixed) | "[" ~ expr.map(Expr.FieldName.Dyn) ~ "]" )
  val assertStmt = P( "assert" ~/ expr ~ (":" ~ expr).? ).map(Expr.Member.AssertStmt.tupled)
  val bind = P( id ~ ("(" ~/ params.? ~ ")").?.map(_.flatten) ~ "=" ~ expr ).map(Expr.Bind.tupled)
  val args = P( ((id ~ "=").? ~ expr).rep(sep = ","~/) ~ ",".? ).map(Expr.Args)

  val params: P[Expr.Params] = P( (id ~ ("=" ~ expr).?).rep(sep = ","~/) ~ ",".? ).map(Expr.Params)

  val binaryop = P( precedenceTable.flatten.sortBy(-_.length).map(LiteralStr).reduce(_ | _) ).!
  val unaryop	= P("-" | "+" | "!" | "~").!

}