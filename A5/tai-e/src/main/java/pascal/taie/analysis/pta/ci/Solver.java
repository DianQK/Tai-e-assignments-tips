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

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;

import java.util.List;
class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        if (callGraph.contains(method)) {
            return;
        }
        callGraph.addReachableMethod(method);
        // S U= Sm(static)
        for (Stmt stmt : method.getIR().getStmts()) {
            stmt.accept(stmtProcessor);
        }

    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        @Override
        public Void visit(New stmt) {
            Pointer pointer = pointerFlowGraph.getVarPtr(stmt.getLValue());
            Obj obj = heapModel.getObj(stmt);
            workList.addEntry(pointer, new PointsToSet(obj));
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            addPFGEdge(pointerFlowGraph.getVarPtr(stmt.getRValue()), pointerFlowGraph.getVarPtr(stmt.getLValue()));
            return null;
        }

        @Override
        public Void visit(LoadField stmt) { // analyze 中 (getStoreFields) 没有 static field 的处理，这里类似 copy 的处理
            if (stmt.isStatic()) {
                addPFGEdge(pointerFlowGraph.getStaticField(stmt.getRValue().getFieldRef().resolve()), pointerFlowGraph.getVarPtr(stmt.getLValue()));
            }
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            if (stmt.isStatic()) {
                addPFGEdge(pointerFlowGraph.getVarPtr(stmt.getRValue()), pointerFlowGraph.getStaticField(stmt.getLValue().getFieldRef().resolve()));
            }
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            if (stmt.isStatic()) {
                processInvoke(stmt, null);
            }
            return null;
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if (pointerFlowGraph.getSuccsOf(source).contains(target)) {
            return;
        }
        pointerFlowGraph.addEdge(source, target);
        if (!source.getPointsToSet().isEmpty()) {
            workList.addEntry(target, source.getPointsToSet());
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            Pointer pointer = entry.pointer();
            PointsToSet diffPointsToSet = propagate(pointer, entry.pointsToSet());
            if (pointer instanceof VarPtr varPtr) {
                Var var = varPtr.getVar();
                for (Obj obj : diffPointsToSet) {
                    for (StoreField storeField : var.getStoreFields()) { // NOTE: 这里不会获取到 static 的语句
                        // (var).field = (storeField.getRValue());
                        addPFGEdge(pointerFlowGraph.getVarPtr(storeField.getRValue()), pointerFlowGraph.getInstanceField(obj, storeField.getFieldRef().resolve()));
                    }
                    for (LoadField loadField : var.getLoadFields()) {
                        addPFGEdge(pointerFlowGraph.getInstanceField(obj, loadField.getFieldRef().resolve()), pointerFlowGraph.getVarPtr(loadField.getLValue()));
                    }
                    for (StoreArray storeArray : var.getStoreArrays()) {
                        addPFGEdge(pointerFlowGraph.getVarPtr(storeArray.getRValue()), pointerFlowGraph.getArrayIndex(obj));
                    }
                    for (LoadArray loadArray : var.getLoadArrays()) {
                        addPFGEdge(pointerFlowGraph.getArrayIndex(obj), pointerFlowGraph.getVarPtr(loadArray.getLValue()));
                    }
                    processCall(var, obj);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        // Diff
        PointsToSet pointsToSetOfPointer = pointer.getPointsToSet();
        PointsToSet diffPointsToSet = new PointsToSet();
        for (Obj obj : pointsToSet) {
            if (!pointsToSetOfPointer.contains(obj)) {
                diffPointsToSet.addObject(obj);
            }
        }
        if (!diffPointsToSet.isEmpty()) {
            for (Obj obj : diffPointsToSet) {
                pointsToSetOfPointer.addObject(obj);
            }
            for (Pointer succPointer : pointerFlowGraph.getSuccsOf(pointer)) {
                workList.addEntry(succPointer, diffPointsToSet);
            }
        }
        return diffPointsToSet;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        for (Invoke invoke : var.getInvokes()) {
            processInvoke(invoke, recv);
        }
    }

    private void processInvoke(Invoke invoke, Obj recv) { // 同时处理多种调用类型, recv == null 是 static
        JMethod method = resolveCallee(recv, invoke);
        if (recv != null) {
            workList.addEntry(pointerFlowGraph.getVarPtr(method.getIR().getThis()), new PointsToSet(recv));
        }
        if (!callGraph.getCalleesOf(invoke).contains(method)) { // NOTE: 不是 contains/add reachable，这里添加和判断的是边
            callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke), invoke, method));
            addReachable(method);
            for (int i = 0; i < method.getParamCount(); i++) {
                addPFGEdge(pointerFlowGraph.getVarPtr(invoke.getInvokeExp().getArg(i)), pointerFlowGraph.getVarPtr(method.getIR().getParam(i)));
            }
            for (Var returnVar : method.getIR().getReturnVars()) {
                if (invoke.getLValue() != null) { // NOTE: 可能没有使用返回值
                    addPFGEdge(pointerFlowGraph.getVarPtr(returnVar), pointerFlowGraph.getVarPtr(invoke.getLValue()));
                }
            }
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
