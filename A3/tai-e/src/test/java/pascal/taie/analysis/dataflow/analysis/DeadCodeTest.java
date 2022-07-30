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

import org.junit.Test;
import pascal.taie.analysis.Tests;

public class DeadCodeTest {

    void testDCD(String inputClass) {
        Tests.test(inputClass, "src/test/resources/dataflow/deadcode/",
                DeadCodeDetection.ID,
                "-a", "livevar=strongly:false",
                "-a", "constprop=edge-refine:false");
    }

    @Test
    public void testControlFlowUnreachable() {
        testDCD("ControlFlowUnreachable");
    }

    @Test
    public void testUnreachableIfBranch() {
        testDCD("UnreachableIfBranch");
    }

    @Test
    public void testUnreachableSwitchBranch() {
        testDCD("UnreachableSwitchBranch");
    }

    @Test
    public void testDeadAssignment() {
        testDCD("DeadAssignment");
    }

    @Test
    public void testLoops() {
        testDCD("Loops");
    }

    @Test
    public void testStoreField() {
        testDCD("StoreField");
    }

}
