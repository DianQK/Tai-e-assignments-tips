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

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);
        Queue<JMethod> worklist = new ArrayDeque<>();
        worklist.add(entry);
        while (!worklist.isEmpty()) {
            JMethod method = worklist.poll();
            if (callGraph.contains(method)) {
                continue;
            }
            callGraph.addReachableMethod(method);
            for (Invoke callSite : callGraph.getCallSitesIn(method)) {
                Set<JMethod> methods = resolve(callSite);
                for (JMethod targetMethod : methods) {
                    callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(callSite), callSite, targetMethod));
                    worklist.add(targetMethod);
                }
            }
        }
        return callGraph;
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        Set<JMethod> methods = new HashSet<>();
        MethodRef methodRef = callSite.getMethodRef();
        Subsignature subsignature = methodRef.getSubsignature();
        if (callSite.isStatic() || callSite.isSpecial()) {
            JClass jclass = methodRef.getDeclaringClass();
            JMethod method = dispatch(jclass, subsignature);
            if (method != null) {
                methods.add(method);
            }
        } else if (callSite.isVirtual() || callSite.isInterface()) {
            JClass jclass = methodRef.getDeclaringClass();
            addSubclassesMethod(jclass, subsignature, methods);
        }
        return methods;
    }

    void addSubclassesMethod(JClass jclass, Subsignature subsignature, Set<JMethod> methods) {
        JMethod method = dispatch(jclass, subsignature);
        if (method != null) {
            methods.add(method);
        }
        Queue<JClass> classQueue = new ArrayDeque<>();
        // NOTE: 不能使用 c = hierarchy.getDirectImplementorsOf(jclass)); c.addAll(hierarchy.getDirectSubinterfacesOf(jclass)); c 是个和内部逻辑共享实例，修改会内部逻辑
        if (jclass.isInterface()) {
            classQueue.addAll(hierarchy.getDirectImplementorsOf(jclass));
            classQueue.addAll(hierarchy.getDirectSubinterfacesOf(jclass));
        } else {
            classQueue.addAll(hierarchy.getDirectSubclassesOf(jclass));
        }
        for (JClass jSubclass : classQueue) {
            this.addSubclassesMethod(jSubclass, subsignature, methods);
        }
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {
        if (jclass == null) {
            return null;
        }
        JMethod method = jclass.getDeclaredMethod(subsignature);
        if (method != null && !method.isAbstract()) {
            return method;
        } else {
            return dispatch(jclass.getSuperClass(), subsignature);
        }
    }
}
