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

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.ArrayIndex;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.element.StaticField;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private PointerAnalysisResult result;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
    }

    void solve() {
        initialize();
        analyze();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        if (callGraph.contains(csMethod)) {
            return;
        }
        callGraph.addReachableMethod(csMethod);
        StmtProcessor stmtProcessor = new StmtProcessor(csMethod);
        for (Stmt stmt : csMethod.getMethod().getIR().getStmts()) {
            stmt.accept(stmtProcessor);
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        @Override
        public Void visit(New stmt) {
            CSVar csVar = csManager.getCSVar(context, stmt.getLValue()); // csVar -> Pointer
            Obj obj = heapModel.getObj(stmt);
            Context selectedContext = contextSelector.selectHeapContext(csMethod, obj);
            CSObj csObj = csManager.getCSObj(selectedContext, heapModel.getObj(stmt));
            workList.addEntry(csVar, PointsToSetFactory.make(csObj));
            return null;
        }

        @Override
        public Void visit(Copy stmt) {
            addPFGEdge(csManager.getCSVar(context, stmt.getRValue()), csManager.getCSVar(context, stmt.getLValue()));
            return null;
        }

        @Override
        public Void visit(LoadField stmt) {
            if (stmt.isStatic()) {
                addPFGEdge(csManager.getStaticField(stmt.getRValue().getFieldRef().resolve()), csManager.getCSVar(context, stmt.getLValue()));
            }
            return null;
        }

        @Override
        public Void visit(StoreField stmt) {
            if (stmt.isStatic()) {
                addPFGEdge(csManager.getCSVar(context, stmt.getRValue()), csManager.getStaticField(stmt.getLValue().getFieldRef().resolve()));
            }
            return null;
        }

        @Override
        public Void visit(Invoke stmt) {
            if (stmt.isStatic()) {
                processInvoke(stmt, null, context);
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
            if (pointer instanceof CSVar csVar) {
                Var var = csVar.getVar();
                for (CSObj csObj : diffPointsToSet) {
                    for (StoreField storeField : var.getStoreFields()) {
                        // x.f = y 指的是：将等式变量的 y 指向 csVar 对应新增的 csObj 中的 field
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), storeField.getRValue()), csManager.getInstanceField(csObj, storeField.getFieldRef().resolve()));
                    }
                    for (LoadField loadField : var.getLoadFields()) {
                        addPFGEdge(csManager.getInstanceField(csObj, loadField.getFieldRef().resolve()), csManager.getCSVar(csVar.getContext(), loadField.getLValue()));
                    }
                    for (StoreArray storeArray : var.getStoreArrays()) {
                        addPFGEdge(csManager.getCSVar(csVar.getContext(), storeArray.getRValue()), csManager.getArrayIndex(csObj));
                    }
                    for (LoadArray loadArray : var.getLoadArrays()) {
                        addPFGEdge(csManager.getArrayIndex(csObj), csManager.getCSVar(csVar.getContext(), loadArray.getLValue()));
                    }
                    processCall(csVar, csObj);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet pointsToSetOfPointer = pointer.getPointsToSet();
        PointsToSet diffPointsToSet = PointsToSetFactory.make();
        for (CSObj csObj : pointsToSet) {
            if (!pointsToSetOfPointer.contains(csObj)) {
                diffPointsToSet.addObject(csObj);
            }
        }
        if (!diffPointsToSet.isEmpty()) {
            for (CSObj csObj : diffPointsToSet) {
                pointsToSetOfPointer.addObject(csObj);
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
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        for (Invoke invoke : recv.getVar().getInvokes()) {
            processInvoke(invoke, recvObj, recv.getContext());
        }
    }

    private void processInvoke(Invoke invoke, CSObj recvObj, Context context) {
        JMethod method = resolveCallee(recvObj, invoke);
        CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
        Context selectedContext = invoke.isStatic() ? contextSelector.selectContext(csCallSite, method) : contextSelector.selectContext(csCallSite, recvObj, method);
        if (recvObj != null) {
            workList.addEntry(csManager.getCSVar(selectedContext, method.getIR().getThis()), PointsToSetFactory.make(recvObj));
        }
        CSMethod csTargetMethod = csManager.getCSMethod(selectedContext, method); // 调用时候都会进入一个新的上下文
        if (!callGraph.getCalleesOf(csCallSite).contains(csTargetMethod)) {
            callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke), csCallSite, csTargetMethod));
            addReachable(csTargetMethod);
            for (int i = 0; i < csTargetMethod.getMethod().getParamCount(); i++) {
                CSVar arg = csManager.getCSVar(csCallSite.getContext(), csCallSite.getCallSite().getInvokeExp().getArg(i));
                CSVar param = csManager.getCSVar(csTargetMethod.getContext(), csTargetMethod.getMethod().getIR().getParam(i));
                addPFGEdge(arg, param);
            }
            for (Var returnVar : csTargetMethod.getMethod().getIR().getReturnVars()) {
                if (csCallSite.getCallSite().getLValue() != null) {
                    CSVar csReturnVar = csManager.getCSVar(csTargetMethod.getContext(), returnVar);
                    CSVar csAssignVar = csManager.getCSVar(csCallSite.getContext(), csCallSite.getCallSite().getLValue());
                    addPFGEdge(csReturnVar, csAssignVar);
                }
            }
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     *             is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
