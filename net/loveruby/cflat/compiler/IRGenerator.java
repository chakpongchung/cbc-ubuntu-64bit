package net.loveruby.cflat.compiler;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.ir.*;
import net.loveruby.cflat.type.Type;
import net.loveruby.cflat.type.TypeRef;
import net.loveruby.cflat.type.TypeTable;
import net.loveruby.cflat.asm.Label;
import net.loveruby.cflat.exception.*;
import java.util.*;

class IRGenerator implements ASTVisitor<Void, Expr> {
    private ErrorHandler errorHandler;
    private TypeTable typeTable;

    // #@@range/ctor{
    public IRGenerator(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    // #@@}

    // #@@range/generate{
    public IR generate(AST ast) throws SemanticException {
        typeTable = ast.typeTable();
        for (DefinedVariable var : ast.definedVariables()) {
            if (var.hasInitializer()) {
                var.setIR(transform(var.initializer()));
            }
        }
        for (DefinedFunction f : ast.definedFunctions()) {
            f.setIR(compileFunctionBody(f));
        }
        if (errorHandler.errorOccured()) {
            throw new SemanticException("Simplify failed.");
        }
        return ast.ir();
    }
    // #@@}

    //
    // Definitions
    //

    private List<Stmt> stmts;
    private LinkedList<LocalScope> scopeStack;
    private LinkedList<Label> breakStack;
    private LinkedList<Label> continueStack;
    private Map<String, JumpEntry> jumpMap;

    public List<Stmt> compileFunctionBody(DefinedFunction f) {
        stmts = new ArrayList<Stmt>();
        scopeStack = new LinkedList<LocalScope>();
        breakStack = new LinkedList<Label>();
        continueStack = new LinkedList<Label>();
        jumpMap = new HashMap<String, JumpEntry>();
        transform(f.body());
        checkJumpLinks(jumpMap);
        return stmts;
    }

    private int beforeStmt;
    private int exprNestLevel = 0;

    private void transform(StmtNode node) {
        beforeStmt = stmts.size();
        node.accept(this);
    }

    private Expr transform(ExprNode node) {
        exprNestLevel++;
        Expr e = node.accept(this);
        exprNestLevel--;
        return e;
    }

    private Expr transformLHS(ExprNode node) {
        Expr result = transform(node);
        if (result instanceof Addr) {
            return ((Addr)result).expr();
        }
        else {
            return result;
        }
    }

    private boolean isStatement() {
        return (exprNestLevel <= 1);
    }

    // insert node before the current statement.
    private void assignBeforeStmt(Expr lhs, Expr rhs) {
        stmts.add(beforeStmt, new Assign(null, lhs, rhs));
        beforeStmt++;
    }

    private void addExprStmt(ExprNode expr) {
        Expr e = transform(expr);
        if (e != null) {
            stmts.add(new ExprStmt(expr.location(), e));
        }
    }

    private void label(Label label) {
        stmts.add(new LabelStmt(null, label));
    }

    private void jump(Label target) {
        stmts.add(new Jump(null, target));
    }

    private void pushBreak(Label label) {
        breakStack.add(label);
    }

    private void popBreak() {
        if (breakStack.isEmpty()) {
            throw new Error("unmatched push/pop for break stack");
        }
        breakStack.removeLast();
    }

    private Label currentBreakTarget() {
        if (breakStack.isEmpty()) {
            throw new JumpError("break from out of loop");
        }
        return breakStack.getLast();
    }

    private void pushContinue(Label label) {
        continueStack.add(label);
    }

    private void popContinue() {
        if (continueStack.isEmpty()) {
            throw new Error("unmatched push/pop for continue stack");
        }
        continueStack.removeLast();
    }

    private Label currentContinueTarget() {
        if (continueStack.isEmpty()) {
            throw new JumpError("continue from out of loop");
        }
        return continueStack.getLast();
    }

    //
    // Statements
    //

    public Void visit(BlockNode node) {
        for (DefinedVariable var : node.variables()) {
            if (var.hasInitializer()) {
                if (var.isPrivate()) {
                    // static variables
                    var.setIR(transform(var.initializer()));
                }
                else {
                    assign(var.location(), ref(var), transform(var.initializer()));
                }
            }
        }
        scopeStack.add(node.scope());
        for (StmtNode s : node.stmts()) {
            transform(s);
        }
        scopeStack.removeLast();
        return null;
    }

    public Void visit(ExprStmtNode node) {
        addExprStmt(node.expr());
        return null;
    }

    public Void visit(IfNode node) {
        Label thenLabel = new Label();
        Label elseLabel = new Label();
        Label endLabel = new Label();

        branch(node.location(),
                transform(node.cond()),
                thenLabel,
                node.elseBody() == null ? endLabel : elseLabel);
        label(thenLabel);
        transform(node.thenBody());
        jump(endLabel);
        if (node.elseBody() != null) {
            label(elseLabel);
            transform(node.elseBody());
            jump(endLabel);
        }
        label(endLabel);
        return null;
    }

    public Void visit(SwitchNode node) {
        List<Case> cases = new ArrayList<Case>();
        Label endLabel = new Label();
        Label defaultLabel = endLabel;

        Expr cond = transform(node.cond());
        for (CaseNode c : node.cases()) {
            if (c.isDefault()) {
                defaultLabel = c.label();
            }
            else {
                for (ExprNode val : c.values()) {
                    Expr v = transform(val);
                    cases.add(new Case(((IntValue)v).value(), c.label()));
                }
            }
        }
        stmts.add(new Switch(node.location(), cond, cases, defaultLabel, endLabel));
        pushBreak(endLabel);
        for (CaseNode c : node.cases()) {
            label(c.label());
            transform(c.body());
        }
        popBreak();
        label(endLabel);
        return null;
    }

    public Void visit(CaseNode node) {
        throw new Error("must not happen");
    }

    public Void visit(WhileNode node) {
        Label begLabel = new Label();
        Label bodyLabel = new Label();
        Label endLabel = new Label();

        label(begLabel);
        branch(node.location(), transform(node.cond()), bodyLabel, endLabel);
        label(bodyLabel);
        pushContinue(begLabel);
        pushBreak(endLabel);
        transform(node.body());
        popBreak();
        popContinue();
        jump(begLabel);
        label(endLabel);
        return null;
    }

    public Void visit(DoWhileNode node) {
        Label begLabel = new Label();
        Label contLabel = new Label();  // before cond (end of body)
        Label endLabel = new Label();

        pushContinue(contLabel);
        pushBreak(endLabel);
        label(begLabel);
        transform(node.body());
        popBreak();
        popContinue();
        label(contLabel);
        branch(node.location(), transform(node.cond()), begLabel, endLabel);
        label(endLabel);
        return null;
    }

    public Void visit(ForNode node) {
        Label begLabel = new Label();
        Label bodyLabel = new Label();
        Label contLabel = new Label();
        Label endLabel = new Label();

        transform(node.init());
        label(begLabel);
        branch(node.location(), transform(node.cond()), bodyLabel, endLabel);
        label(bodyLabel);
        pushContinue(contLabel);
        pushBreak(endLabel);
        transform(node.body());
        popBreak();
        popContinue();
        label(contLabel);
        transform(node.incr());
        jump(begLabel);
        label(endLabel);
        return null;
    }

    public Void visit(BreakNode node) {
        try {
            jump(currentBreakTarget());
        }
        catch (JumpError err) {
            error(node, err.getMessage());
        }
        return null;
    }

    public Void visit(ContinueNode node) {
        try {
            jump(currentContinueTarget());
        }
        catch (JumpError err) {
            error(node, err.getMessage());
        }
        return null;
    }

    public Void visit(LabelNode node) {
        try {
            stmts.add(new LabelStmt(node.location(),
                    defineLabel(node.name(), node.location())));
            if (node.stmt() != null) {
                transform(node.stmt());
            }
        }
        catch (SemanticException ex) {
            error(node, ex.getMessage());
        }
        return null;
    }

    public Void visit(GotoNode node) {
        stmts.add(new Jump(node.location(), referLabel(node.target())));
        return null;
    }

    public Void visit(ReturnNode node) {
        stmts.add(new Return(node.location(),
                node.expr() == null ? null : transform(node.expr())));
        return null;
    }

    private void branch(Location loc, Expr cond, Label thenLabel, Label elseLabel) {
        stmts.add(new BranchIf(loc, cond, thenLabel, elseLabel));
    }

    private void assign(Expr lhs, Expr rhs) {
        assign(null, lhs, rhs);
    }

    private void assign(Location loc, Expr lhs, Expr rhs) {
        stmts.add(new Assign(loc, lhs, rhs));
    }

    private DefinedVariable tmpVar(Type t) {
        return scopeStack.getLast().allocateTmp(t);
    }

    class JumpEntry {
        public Label label;
        public long numRefered;
        public boolean isDefined;
        public Location location;

        public JumpEntry(Label label) {
            this.label = label;
            numRefered = 0;
            isDefined = false;
        }
    }

    private Label defineLabel(String name, Location loc)
                                    throws SemanticException {
        JumpEntry ent = getJumpEntry(name);
        if (ent.isDefined) {
            throw new SemanticException(
                "duplicated jump labels in " + name + "(): " + name);
        }
        ent.isDefined = true;
        ent.location = loc;
        return ent.label;
    }

    private Label referLabel(String name) {
        JumpEntry ent = getJumpEntry(name);
        ent.numRefered++;
        return ent.label;
    }

    private JumpEntry getJumpEntry(String name) {
        JumpEntry ent = jumpMap.get(name);
        if (ent == null) {
            ent = new JumpEntry(new Label());
            jumpMap.put(name, ent);
        }
        return ent;
    }

    private void checkJumpLinks(Map<String, JumpEntry> jumpMap) {
        for (Map.Entry<String, JumpEntry> ent : jumpMap.entrySet()) {
            String labelName = ent.getKey();
            JumpEntry jump = ent.getValue();
            if (!jump.isDefined) {
                errorHandler.error(jump.location,
                        "undefined label: " + labelName);
            }
            if (jump.numRefered == 0) {
                errorHandler.warn(jump.location,
                        "useless label: " + labelName);
            }
        }
    }

    //
    // Expressions (with side effects)
    //

    public Expr visit(CondExprNode node) {
        Label thenLabel = new Label();
        Label elseLabel = new Label();
        Label endLabel = new Label();
        DefinedVariable var = tmpVar(node.type());

        Expr cond = transform(node.cond());
        branch(node.location(), cond, thenLabel, elseLabel);
        label(thenLabel);
        assign(ref(var), transform(node.thenExpr()));
        jump(endLabel);
        label(elseLabel);
        assign(ref(var), transform(node.elseExpr()));
        jump(endLabel);
        label(endLabel);
        return ref(var);
    }

    public Expr visit(LogicalAndNode node) {
        Label rightLabel = new Label();
        Label endLabel = new Label();
        DefinedVariable var = tmpVar(node.type());

        assign(ref(var), transform(node.left()));
        branch(node.location(), ref(var), rightLabel, endLabel);
        label(rightLabel);
        assign(ref(var), transform(node.right()));
        label(endLabel);
        return ref(var);
    }

    public Expr visit(LogicalOrNode node) {
        Label rightLabel = new Label();
        Label endLabel = new Label();
        DefinedVariable var = tmpVar(node.type());

        assign(ref(var), transform(node.left()));
        branch(node.location(), ref(var), endLabel, rightLabel);
        label(rightLabel);
        assign(ref(var), transform(node.right()));
        label(endLabel);
        return ref(var);
    }

    public Expr visit(AssignNode node) {
        if (isStatement()) {
            assign(transformLHS(node.lhs()), transform(node.rhs()));
            return null;
        }
        else {
            DefinedVariable tmp = tmpVar(node.rhs().type());
            assignBeforeStmt(ref(tmp), transform(node.rhs()));
            assignBeforeStmt(transformLHS(node.lhs()), ref(tmp));
            return ref(tmp);
        }
    }

    public Expr visit(OpAssignNode node) {
        // evaluate rhs before lhs.
        Expr rhs = transform(node.rhs());
        Expr lhs = transformLHS(node.lhs());
        return transformOpAssign(lhs, Op.internBinary(node.operator()), rhs);
    }

    private Expr transformOpAssign(Expr lhs, Op op, Expr _rhs) {
        Expr rhs = expandPointerArithmetic(_rhs, op, lhs.type());
        if (isStatement()) {
            if (lhs.isConstantAddress()) {
                // lhs = lhs op rhs
                assign(lhs, new Bin(lhs.type(), op, lhs, rhs));
            }
            else {
                // a = &lhs, *a = *a op rhs
                Expr addr = addressOf(lhs);
                DefinedVariable a = tmpVar(addr.type());
                assign(ref(a), addr);
                assign(deref(a), new Bin(lhs.type(), op, deref(a), rhs));
            }
            return null;
        }
        else {
            // a = &lhs, *a = *a op rhs, *a
            Expr addr = addressOf(lhs);
            DefinedVariable a = tmpVar(addr.type());
            assignBeforeStmt(ref(a), addr);
            assignBeforeStmt(deref(a), new Bin(lhs.type(), op, deref(a), rhs));
            return deref(a);
        }
    }

    private Expr expandPointerArithmetic(Expr rhs, Op op, Type lhsType) {
        switch (op) {
        case ADD:
        case SUB:
            if (lhsType.isDereferable()) {
                return new Bin(rhs.type(), Op.MUL,
                    rhs, ptrDiff(lhsType.baseType().size()));
            }
        }
        return rhs;
    }

    // transform node into: lhs += 1 or lhs -= 1
    public Expr visit(PrefixOpNode node) {
        return transformOpAssign(transformLHS(node.expr()),
                binOp(node.operator()),
                intValue(1));
    }

    public Expr visit(SuffixOpNode node) {
        Expr lhs = transformLHS(node.expr());
        Op op = binOp(node.operator());
        if (isStatement()) {
            // expr++; -> expr += 1;
            return transformOpAssign(lhs, op, intValue(1));
        }
        else if (lhs.isConstantAddress()) {
            // f(expr++) -> v = expr; expr = expr + 1, f(v)
            DefinedVariable v = tmpVar(lhs.type());
            assignBeforeStmt(ref(v), lhs);
            Expr rhs = expandPointerArithmetic(intValue(1), op, lhs.type());
            assignBeforeStmt(lhs, new Bin(lhs.type(), op, lhs, rhs));
            return ref(v);
        }
        else {
            // f(expr++) -> a = &expr, v = *a; *a = *a + 1, f(v)
            Expr addr = addressOf(lhs);
            DefinedVariable a = tmpVar(addr.type());
            DefinedVariable v = tmpVar(lhs.type());
            assignBeforeStmt(ref(a), addr);
            assignBeforeStmt(ref(v), deref(a));
            assignBeforeStmt(deref(a),
                new Bin(lhs.type(), op,
                    deref(a),
                    expandPointerArithmetic(intValue(1), op, lhs.type())));
            return ref(v);
        }
    }

    public Expr visit(FuncallNode node) {
        List<Expr> newArgs = new ArrayList<Expr>();
        ListIterator<ExprNode> args = node.finalArg();
        while (args.hasPrevious()) {
            newArgs.add(0, transform(args.previous()));
        }
        return new Call(node.type(), transform(node.expr()), newArgs);
    }

    //
    // Expressions (no side effects)
    //

    // #@@range/BinaryOpNode{
    public Expr visit(BinaryOpNode node) {
        Expr left = transform(node.left());
        Expr right = transform(node.right());
        if (node.operator().equals("+") || node.operator().equals("-")) {
            if (left.type().isDereferable()) {
                right = new Bin(right.type(), Op.MUL,
                        right, ptrDiff(left.type().baseType().size()));
            }
            else if (right.type().isDereferable()) {
                left = new Bin(left.type(), Op.MUL,
                        left, ptrDiff(right.type().baseType().size()));
            }
        }
        return new Bin(node.type(), Op.internBinary(node.operator()), left, right);
    }
    // #@@}

    public Expr visit(UnaryOpNode node) {
        if (node.operator().equals("+")) {
            // +expr -> expr
            return transform(node.expr());
        }
        else {
            return new Uni(node.type(), Op.internUnary(node.operator()),
                    transform(node.expr()));
        }
    }

    public Expr visit(ArefNode node) {
        Expr offset = new Bin(typeTable.signedInt(), Op.MUL,
                intValue(node.elementSize()), transformArrayIndex(node));
        return deref(
            new Bin(pointerTo(node.type()), Op.ADD,
                transform(node.baseExpr()), offset));
    }

    // For multidimension array: t[e][d][c][b][a];
    // &a[a0][b0][c0][d0][e0]
    //     = &a + edcb*a0 + edc*b0 + ed*c0 + e*d0 + e0
    //     = &a + (((((a0)*b + b0)*c + c0)*d + d0)*e + e0) * sizeof(t)
    //
    // #@@range/transformArrayIndex{
    private Expr transformArrayIndex(ArefNode node) {
        if (node.isMultiDimension()) {
            return new Bin(
                    typeTable.signedInt(), Op.ADD,
                    transform(node.index()),
                    new Bin(typeTable.signedInt(), Op.MUL,
                            intValue(node.length()),
                            transformArrayIndex((ArefNode)node.expr())));
        }
        else {
            return transform(node.index());
        }
    }
    // #@@}

    public Expr visit(MemberNode node) {
        Expr addr = new Bin(pointerTo(node.type()), Op.ADD,
            addressOf(transform(node.expr())),
            intValue(node.offset()));
        return node.shouldEvaluatedToAddress() ? addr : deref(addr);
    }

    public Expr visit(PtrMemberNode node) {
        Expr addr = new Bin(pointerTo(node.type()), Op.ADD,
            transform(node.expr()),
            intValue(node.offset()));
        return node.shouldEvaluatedToAddress() ? addr : deref(addr);
    }

    public Expr visit(DereferenceNode node) {
        return new Mem(node.type(), transform(node.expr()));
    }

    public Expr visit(AddressNode node) {
        Expr e = transform(node.expr());
        if (node.expr().shouldEvaluatedToAddress() && (e instanceof Addr)) {
            return e;
        }
        else {
            return addressOf(e);
        }
    }

    public Expr visit(CastNode node) {
        if (node.isEffectiveCast()) {
            return new Uni(node.type(), Op.CAST, transform(node.expr()));
        }
        else {
            return transform(node.expr());
        }
    }

    public Expr visit(SizeofExprNode node) {
        return intValue(node.expr().type().allocSize());
    }

    public Expr visit(SizeofTypeNode node) {
        return intValue(node.operand().allocSize());
    }

    public Expr visit(VariableNode node) {
        Var var = new Var(node.entity());
        return node.shouldEvaluatedToAddress() ? addressOf(var) : var;
    }

    public Expr visit(IntegerLiteralNode node) {
        return new IntValue(node.type(), node.value());
    }

    public Expr visit(StringLiteralNode node) {
        return new StringValue(node.type(), node.entry());
    }

    //
    // Utilities
    //

    // unary ops -> binary ops
    private Op binOp(String uniOp) {
        return uniOp.equals("++") ? Op.ADD : Op.SUB;
    }

    // add AddressNode on top of the expr.
    private Expr addressOf(Expr expr) {
        if (expr instanceof Mem) {
            return ((Mem)expr).expr();
        }
        else {
            Type base = expr.type();
            Type t = shouldEvalutedToAddress(expr) ? base : pointerTo(base);
            return new Addr(t, expr);
        }
    }

    private boolean shouldEvalutedToAddress(Expr expr) {
        return expr.type().isArray()
            || ((expr instanceof Var) && ((Var)expr).entity().cannotLoad());
    }

    private Var ref(DefinedVariable var) {
        return new Var(var);
    }

    // add DereferenceNode on top of the var.
    private Mem deref(DefinedVariable var) {
        return deref(ref(var));
    }

    // add DereferenceNode on top of the expr.
    private Mem deref(Expr expr) {
        return new Mem(expr.type().baseType(), expr);
    }

    private Type pointerTo(Type t) {
        return typeTable.pointerTo(t);
    }

    private IntValue intValue(long n) {
        return new IntValue(typeTable.signedInt(), n);
    }

    private IntValue ptrDiff(long n) {
        return new IntValue(typeTable.ptrDiffType(), n);
    }

    private void bindType(TypeNode t) {
        t.setType(typeTable.get(t.typeRef()));
    }

    private void error(Node n, String msg) {
        errorHandler.error(n.location(), msg);
    }
}