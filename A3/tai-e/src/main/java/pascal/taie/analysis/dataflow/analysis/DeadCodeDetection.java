/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.dataflow.analysis;

import pascal.taie.analysis.MethodAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.Edge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.RValue;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.AssignStmt;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.SwitchStmt;

import java.util.Queue;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

public class DeadCodeDetection extends MethodAnalysis {

    public static final String ID = "deadcode";

    public DeadCodeDetection(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Set<Stmt> analyze(IR ir) {
        // obtain CFG
        CFG<Stmt> cfg = ir.getResult(CFGBuilder.ID);
        // obtain result of constant propagation
        DataflowResult<Stmt, CPFact> constants =
                ir.getResult(ConstantPropagation.ID);
        // obtain result of live variable analysis
        DataflowResult<Stmt, SetFact<Var>> liveVars =
                ir.getResult(LiveVariableAnalysis.ID);
        // keep statements (dead code) sorted in the resulting set
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        // Your task is to recognize dead code in ir and add it to deadCode
        Set<Stmt> reachableCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        reachableCode.add(cfg.getExit()); // Exit 一定可达鸭
        Queue<Stmt> worklist = new ArrayDeque<>();
        worklist.add(cfg.getEntry());
        while (!worklist.isEmpty()) {
            Stmt stmt = worklist.poll();
            if (reachableCode.contains(stmt)) {
                continue;
            }
            reachableCode.add(stmt);
            Set<Edge<Stmt>> edges = cfg.getOutEdgesOf(stmt); // 控制流不可达代码根本不会在 OutEdges 中出现
            if (stmt instanceof If ifStmt) { // if 语句会得到两个不同的边，edge target 也不同
                Value value = ConstantPropagation.evaluate(ifStmt.getCondition(), constants.getInFact(stmt));
                if (!value.isConstant()) {
                    worklist.addAll(cfg.getSuccsOf(stmt));
                    continue;
                }
                for (Edge<Stmt> edge : edges) {
                    if ((edge.getKind() == Edge.Kind.IF_TRUE && value.getConstant() >= 1) || (edge.getKind() == Edge.Kind.IF_FALSE && value.getConstant() <= 0)) {
                        worklist.add(edge.getTarget());
                        break;
                    }
                }
            } else if (stmt instanceof SwitchStmt switchStmt) {
                Value value = constants.getResult(switchStmt).get(switchStmt.getVar());
                if (!value.isConstant()) {
                    worklist.addAll(cfg.getSuccsOf(stmt));
                    continue;
                }
                int switchConstant = value.getConstant();
                boolean matched = false;
                for (Edge<Stmt> edge : edges) { // 也可以用 switchStmt.getCaseTargets ?
                    if (edge.getKind() == Edge.Kind.SWITCH_CASE && edge.getCaseValue() == switchConstant) {
                        worklist.add(edge.getTarget());
                        matched = true;
                        break; // case 1 {}; case 1 {}; 只匹配第一个
                    }
                }
                if (!matched) {
                    worklist.add(switchStmt.getDefaultTarget());
                }
            } else if (stmt instanceof AssignStmt assignStmt) {
                worklist.addAll(cfg.getSuccsOf(stmt)); // 赋值语句不影响控制流
                if (!hasNoSideEffect(assignStmt.getRValue())) {
                    continue;
                }
                if (assignStmt.getLValue() instanceof Var var) {
                    SetFact<Var> assignLiveVars = liveVars.getResult(stmt);
                    if (!assignLiveVars.contains(var)) {
                        reachableCode.remove(stmt); // 可能不是一个合适的处理
                    }
                }
            } else {
                worklist.addAll(cfg.getSuccsOf(stmt));
            }
        }
        for (Stmt stmt : cfg) {
            if (!reachableCode.contains(stmt)) {
                deadCode.add(stmt);
            }
        }
        return deadCode;
    }

    /**
     * @return true if given RValue has no side effect, otherwise false.
     */
    private static boolean hasNoSideEffect(RValue rvalue) {
        // new expression modifies the heap
        if (rvalue instanceof NewExp ||
                // cast may trigger ClassCastException
                rvalue instanceof CastExp ||
                // static field access may trigger class initialization
                // instance field access may trigger NPE
                rvalue instanceof FieldAccess ||
                // array access may trigger NPE
                rvalue instanceof ArrayAccess) {
            return false;
        }
        if (rvalue instanceof ArithmeticExp) {
            ArithmeticExp.Op op = ((ArithmeticExp) rvalue).getOperator();
            // may trigger DivideByZeroException
            return op != ArithmeticExp.Op.DIV && op != ArithmeticExp.Op.REM;
        }
        return true;
    }
}
