package zio.sql.postgresql

import zio.sql.Jdbc

trait PostgresModule extends Jdbc { self =>

  override def renderRead(read: self.Read[_]): String = {
    val builder = new StringBuilder

    def buildExpr[A, B](expr: self.Expr[_, A, B]): Unit = expr match {
      case Expr.Source(tableName, column)                               =>
        val _ = builder.append(tableName).append(".").append(column.name)
      case Expr.Unary(base, op)                                         =>
        val _ = builder.append(" ").append(op.symbol)
        buildExpr(base)
      case Expr.Property(base, op)                                      =>
        buildExpr(base)
        val _ = builder.append(" ").append(op.symbol)
      case Expr.Binary(left, right, op)                                 =>
        buildExpr(left)
        builder.append(" ").append(op.symbol).append(" ")
        buildExpr(right)
      case Expr.Relational(left, right, op)                             =>
        buildExpr(left)
        builder.append(" ").append(op.symbol).append(" ")
        buildExpr(right)
      case Expr.In(value, set)                                          =>
        buildExpr(value)
        buildReadString(set)
      case Expr.Literal(value)                                          =>
        val _ = builder.append(value.toString) //todo fix escaping
      case Expr.AggregationCall(param, aggregation)                     =>
        builder.append(aggregation.name.name)
        builder.append("(")
        buildExpr(param)
        val _ = builder.append(")")
      case Expr.FunctionCall1(param, function)                          =>
        builder.append(function.name.name)
        builder.append("(")
        buildExpr(param)
        val _ = builder.append(")")
      case Expr.FunctionCall2(param1, param2, function)                 =>
        builder.append(function.name.name)
        builder.append("(")
        buildExpr(param1)
        builder.append(",")
        buildExpr(param2)
        val _ = builder.append(")")
      case Expr.FunctionCall3(param1, param2, param3, function)         =>
        builder.append(function.name.name)
        builder.append("(")
        buildExpr(param1)
        builder.append(",")
        buildExpr(param2)
        builder.append(",")
        buildExpr(param3)
        val _ = builder.append(")")
      case Expr.FunctionCall4(param1, param2, param3, param4, function) =>
        builder.append(function.name.name)
        builder.append("(")
        buildExpr(param1)
        builder.append(",")
        buildExpr(param2)
        builder.append(",")
        buildExpr(param3)
        builder.append(",")
        buildExpr(param4)
        val _ = builder.append(")")
    }

    def buildReadString[A <: SelectionSet[_]](read: self.Read[_]): Unit =
      read match {
        //todo offset (needs orderBy, must use fetch _instead_ of top)
        case Read.Select(selection, table, whereExpr, groupBy, havingExpr, orderBy, offset, limit) =>
          builder.append("select ")
          buildSelection(selection.value)
          builder.append(" from ")
          buildTable(table)
          whereExpr match {
            case Expr.Literal(true) => ()
            case _                  =>
              builder.append(" where ")
              buildExpr(whereExpr)
          }
          groupBy match {
            case _ :: _ =>
              builder.append(" group by ")
              buildExprList(groupBy)

              havingExpr match {
                case Expr.Literal(true) => ()
                case _                  =>
                  builder.append(" having ")
                  buildExpr(havingExpr)
              }
            case Nil    => ()
          }
          orderBy match {
            case _ :: _ =>
              builder.append(" order by ")
              buildOrderingList(orderBy)
            case Nil    => ()
          }
          limit match {
            case Some(limit) =>
              builder.append(" limit ").append(limit)
            case None        => ()
          }
          offset match {
            case Some(offset) =>
              val _ = builder.append(" offset ").append(offset)
            case None         => ()
          }

        case Read.Union(left, right, distinct) =>
          buildReadString(left)
          builder.append(" union ")
          if (!distinct) builder.append("all ")
          buildReadString(right)

        case Read.Literal(values) =>
          val _ = builder.append(" (").append(values.mkString(",")).append(") ") //todo fix needs escaping
      }

    def buildExprList(expr: List[Expr[_, _, _]]): Unit               =
      expr match {
        case head :: tail =>
          buildExpr(head)
          tail match {
            case _ :: _ =>
              builder.append(", ")
              buildExprList(tail)
            case Nil    => ()
          }
        case Nil          => ()
      }
    def buildOrderingList(expr: List[Ordering[Expr[_, _, _]]]): Unit =
      expr match {
        case head :: tail =>
          head match {
            case Ordering.Asc(value)  => buildExpr(value)
            case Ordering.Desc(value) =>
              buildExpr(value)
              builder.append(" desc")
          }
          tail match {
            case _ :: _ =>
              builder.append(", ")
              buildOrderingList(tail)
            case Nil    => ()
          }
        case Nil          => ()
      }

    def buildSelection[A](selectionSet: SelectionSet[A]): Unit =
      selectionSet match {
        case SelectionSet.Cons(head, tail) =>
          buildColumnSelection(head)
          tail match {
            case SelectionSet.Empty => ()
            case _                  =>
              builder.append(", ")
              buildSelection(tail)
          }
        case SelectionSet.Empty            => ()
      }

    def buildColumnSelection[A, B](columnSelection: ColumnSelection[A, B]): Unit =
      columnSelection match {
        case ColumnSelection.Constant(value, name) =>
          builder.append(value.toString()) //todo fix escaping
          name match {
            case Some(name) =>
              val _ = builder.append(" as ").append(name)
            case None       => ()
          }
        case ColumnSelection.Computed(expr, name)  =>
          buildExpr(expr)
          name match {
            case Some(name) =>
              Expr.exprName(expr) match {
                case Some(sourceName) if name != sourceName =>
                  val _ = builder.append(" as ").append(name)
                case _                                      => ()
              }
            case _          => () //todo what do we do if we don't have a name?
          }
      }
    def buildTable(table: Table): Unit                                           =
      table match {
        //The outer reference in this type test cannot be checked at run time?!
        case sourceTable: self.Table.Source          =>
          val _ = builder.append(sourceTable.name)
        case Table.Joined(joinType, left, right, on) =>
          buildTable(left)
          builder.append(joinType match {
            case JoinType.Inner      => " inner join "
            case JoinType.LeftOuter  => " left join "
            case JoinType.RightOuter => " right join "
            case JoinType.FullOuter  => " outer join "
          })
          buildTable(right)
          builder.append(" on ")
          buildExpr(on)
          val _ = builder.append(" ")
      }
    buildReadString(read)
    builder.toString()
  }
}
