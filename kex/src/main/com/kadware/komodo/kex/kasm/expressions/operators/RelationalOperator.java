/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm.expressions.operators;

import com.kadware.komodo.kex.kasm.Assembler;
import com.kadware.komodo.kex.kasm.Locale;
import com.kadware.komodo.kex.kasm.dictionary.IntegerValue;
import com.kadware.komodo.kex.kasm.dictionary.FloatingPointValue;
import com.kadware.komodo.kex.kasm.dictionary.Value;
import com.kadware.komodo.kex.kasm.dictionary.ValueType;
import com.kadware.komodo.kex.kasm.exceptions.ExpressionException;
import com.kadware.komodo.kex.kasm.exceptions.TypeException;
import java.util.Stack;

/**
 * Base class for infix relational operators
 */
@SuppressWarnings("Duplicates")
public abstract class RelationalOperator extends Operator {

    RelationalOperator(Locale locale) { super(locale); }

    /**
     * Evaluator
     * @param assembler context
     * @param valueStack stack of values - we pop one or two from here, and push one back
     * @throws ExpressionException if something goes wrong with the process
     */
    @Override
    public abstract void evaluate(
        final Assembler assembler,
        final Stack<Value> valueStack
    ) throws ExpressionException;

    /**
     * Wrapper around base class getOperands() method.
     * We call that method, then adjust the results before passing them up to the caller as follows:
     *      If the operands are of differing types:
     *          If either operand is floating point, the other is converted to floating point.
     *          Else, if either operand is integer, the other is converted to integer.
     *      Else, the operands are returned unchanged
     * If we find any invalid types, we post one or more assembler, and we throw TypeException.
     * @param valueStack the value stack from which we get the operators
     * @param assembler object to which we post any necessary assembler
     * @return left-hand possibly adjusted operand in result[0], right-hand in result[1]
     * @throws TypeException if either operand is other than floating point, integer, or string
     */
    protected Value[] getTransformedOperands(
        final Stack<Value> valueStack,
        final Assembler assembler
    ) throws TypeException {
        Value[] operands = super.getOperands(valueStack);
        ValueType opType0 = operands[0].getType();
        ValueType opType1 = operands[1].getType();

        if (opType0 != opType1) {
            if (opType0 == ValueType.FloatingPoint) {
                if (opType1 == ValueType.Integer) {
                    operands[1] = FloatingPointValue.convertFromInteger(_locale, (IntegerValue) operands[1]);
                } else {
                    postValueDiagnostic(false, assembler);
                    throw new TypeException();
                }
            } else if (opType0 == ValueType.Integer) {
                if (opType1 == ValueType.FloatingPoint) {
                    operands[0] = FloatingPointValue.convertFromInteger(_locale, (IntegerValue) operands[0]);
                } else {
                    postValueDiagnostic(false, assembler);
                    throw new TypeException();
                }
            } else if (opType0 == ValueType.String) {
                //  RHS is  not acceptable for LHS of String
                postValueDiagnostic(false, assembler);
                throw new TypeException();
            } else {
                //  LHS is not acceptable, RHS may not be acceptable
                postValueDiagnostic(true, assembler);
                switch (operands[1].getType()) {
                    case Integer:
                    case FloatingPoint:
                    case String:
                        break;

                    default:
                        postValueDiagnostic(false, assembler);
                }
                throw new TypeException();
            }
        } else {
            //  The types are identical.  Check one side to make sure the type is valid.
            switch (operands[0].getType()) {
                case Integer:
                case FloatingPoint:
                case String:
                    break;

                default:
                    postValueDiagnostic(true, assembler);
                    postValueDiagnostic(false, assembler);
                    throw new TypeException();
            }
        }

        return operands;
    }

    @Override public final int getPrecedence() { return 2; }
    @Override public final Type getType() { return Type.Infix; }
}
