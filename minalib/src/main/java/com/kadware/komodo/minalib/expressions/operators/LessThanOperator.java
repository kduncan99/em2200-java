/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.minalib.expressions.operators;

import com.kadware.komodo.minalib.*;
import com.kadware.komodo.minalib.dictionary.*;
import com.kadware.komodo.minalib.diagnostics.RelocationDiagnostic;
import com.kadware.komodo.minalib.exceptions.*;

import java.util.Stack;

/**
 * Class for less-than operator
 */
public class LessThanOperator extends RelationalOperator {

    /**
     * Constructor
     * @param locale location of operator
     */
    public LessThanOperator(
        final Locale locale
    ) {
        super(locale);
    }

    /**
     * Evaluator
     * @param context current contextual information one of our subclasses might need to know
     * @param valueStack stack of values - we pop one or two from here, and push one back
     * @throws ExpressionException if something goes wrong with the process
     */
    @Override
    public void evaluate(
        final Context context,
        Stack<Value> valueStack
    ) throws ExpressionException {
        try {
            Value[] operands = getTransformedOperands(valueStack, context.getDiagnostics());
            int result = (operands[0].compareTo(operands[1]) < 0) ? 1 : 0;
            valueStack.push( new IntegerValue(false, result, null ) );
        } catch (RelocationException ex) {
            //  thrown by compareTo() - we need to post a diag
            context.appendDiagnostic(new RelocationDiagnostic(getLocale()));
            throw new ExpressionException();
        } catch (TypeException ex) {
            //  thrown by getTransformedOperands() - diagnostic already posted
            //  can be thrown by compareTo() - but won't be because we already prevented it in the previous call
            throw new ExpressionException();
        }
    }
}