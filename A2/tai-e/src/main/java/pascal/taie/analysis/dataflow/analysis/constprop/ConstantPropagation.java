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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.BinaryExp;
import pascal.taie.ir.exp.BitwiseExp;
import pascal.taie.ir.exp.ConditionExp;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.ShiftExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        CPFact fact = new CPFact();
        for (Var param : cfg.getIR().getParams()) {
            if (canHoldInt(param)) { // https://github.com/MirageLyu/Tai-e-assignments/commit/a1b827267773a3f0b40875a01a37f3d75608c2f2#diff-0de033441703a7c75d3092bebfa08ac64ee9e7a8b88e66d7e62872f7ece75677R59
                fact.update(param, Value.getNAC());
            }
        }
        return fact;
    }

    @Override
    public CPFact newInitialFact() {
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        for (Var var : fact.keySet()) {
            target.update(var, this.meetValue(fact.get(var), target.get(var)));
        }
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        if (v1.isConstant()) {
            if (v2.isConstant()) {
                return v1.getConstant() == v2.getConstant() ? v1 : Value.getNAC();
            } else {
                return v1;
            }
        } else {
            return v2;
        }
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        if (stmt instanceof DefinitionStmt definitionStmt && definitionStmt.getLValue() instanceof Var varDef && canHoldInt(varDef)) {
            // gen
            Exp exp = definitionStmt.getRValue();
            Value evaluateValue = evaluate(exp, in);
            boolean changed = out.update(varDef, evaluateValue);
            // U (IN[s] – {(x, _)})
            CPFact inCopy = in.copy();
            inCopy.remove(varDef);
            changed |= out.copyFrom(inCopy);
            return changed;
        } else {
            return out.copyFrom(in);
        }
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {
        if (exp instanceof IntLiteral useIntLiteral) { // x = c
            return Value.makeConstant(useIntLiteral.getValue());
        } else if (exp instanceof Var var) { // x = y
            return in.get(var);
        }
        if (!(exp instanceof BinaryExp binaryExp)) {
            return Value.getNAC();
        }
        Var operand2 = binaryExp.getOperand2();
        Value value2 = in.get(operand2);
        if (!value2.isConstant()) { // v2: NAC/Undef -> Result: NAC/Undef
            return value2;
        }
        // v1: NAC/Undef && v2: Constant
        int value2Constant = value2.getConstant();
        if (value2Constant == 0 && binaryExp instanceof ArithmeticExp arithmeticExp) { // v1: Any && v2: 0 -> Result: Undef(/%)/NAC
            return switch (arithmeticExp.getOperator()) {
                case DIV, REM -> Value.getUndef();
                default -> Value.getNAC();
            };
        }
        Var operand1 = binaryExp.getOperand1();
        Value value1 = in.get(operand1);
        if (!value1.isConstant()) { // v1: NAC/Undef && v2: Constant(!0)
            return value1;
        }
        // v1: Constant && v2: Constant(!0)
        int value1Constant = value1.getConstant();
        if (binaryExp instanceof ArithmeticExp arithmeticExp) {
            return switch (arithmeticExp.getOperator()) {
                case ADD -> Value.makeConstant(value1Constant + value2Constant);
                case DIV -> Value.makeConstant(value1Constant / value2Constant);
                case REM -> Value.makeConstant(value1Constant % value2Constant);
                case MUL -> Value.makeConstant(value1Constant * value2Constant);
                case SUB -> Value.makeConstant(value1Constant - value2Constant);
            };
        } else if (binaryExp instanceof ConditionExp conditionExp) {
            return switch (conditionExp.getOperator()) {
                case EQ -> Value.makeConstant(value1Constant == value2Constant ? 1 : 0);
                case GE -> Value.makeConstant(value1Constant >= value2Constant ? 1 : 0);
                case GT -> Value.makeConstant(value1Constant > value2Constant ? 1 : 0);
                case LE -> Value.makeConstant(value1Constant <= value2Constant ? 1 : 0);
                case LT -> Value.makeConstant(value1Constant < value2Constant ? 1 : 0);
                case NE -> Value.makeConstant(value1Constant != value2Constant ? 1 : 0);
            };
        } else if (binaryExp instanceof ShiftExp shiftExp) {
            return switch (shiftExp.getOperator()) {
                case SHL -> Value.makeConstant(value1Constant << value2Constant);
                case SHR -> Value.makeConstant(value1Constant >> value2Constant);
                case USHR -> Value.makeConstant(value1Constant >>> value2Constant);
            };
        } else if (binaryExp instanceof BitwiseExp bitwiseExp) {
            return switch (bitwiseExp.getOperator()) {
                case OR -> Value.makeConstant(value1Constant | value2Constant);
                case AND -> Value.makeConstant(value1Constant & value2Constant);
                case XOR -> Value.makeConstant(value1Constant ^ value2Constant);
            };
        } else {
            return Value.getNAC();
        }
    }
}
