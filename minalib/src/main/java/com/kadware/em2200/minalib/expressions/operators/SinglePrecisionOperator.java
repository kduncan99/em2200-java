/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.minalib.expressions.operators;

import com.kadware.em2200.minalib.*;
import com.kadware.em2200.minalib.diagnostics.*;
import com.kadware.em2200.minalib.dictionary.*;
import com.kadware.em2200.minalib.exceptions.*;
import java.util.Stack;

/**
 * Class for single-precision (trailing 'S') operator
 */
public class SinglePrecisionOperator extends Operator {

    /**
     * Constructor
     * <p>
     * @param locale
     */
    public SinglePrecisionOperator(
        final Locale locale
    ) {
        super(locale);
    }

    /**
     * Getter
     * <p>
     * @return
     */
    @Override
    public int getPrecedence(
    ) {
        return 10;
    }

    /**
     * Getter
     * <p>
     * @return
     */
    @Override
    public Type getType(
    ) {
        return Type.Postfix;
    }

    /**
     * Evaluator
     * <p>
     * @param context current contextual information one of our subclasses might need to know
     * @param valueStack stack of values - we pop one or two from here, and push one back
     * @param diagnostics where we append diagnostics if necessary
     * <p>
     * @throws ExpressionException if something goes wrong with the process
     */
    @Override
    public void evaluate(
        final Context context,
        Stack<Value> valueStack,
        Diagnostics diagnostics
    ) throws ExpressionException {
        try {
            Value operand = getOperands(valueStack)[0];
            switch (operand.getType()) {
                case Integer:
                case FloatingPoint:
                case String:
                    valueStack.push(operand.copy(Precision.Single));
                    break;

                default:
                    postValueDiagnostic(true, diagnostics);
                    throw new ExpressionException();
            }
        } catch (TypeException ex) {
            postValueDiagnostic(true, diagnostics);
            throw new ExpressionException();
        }
    }
}
