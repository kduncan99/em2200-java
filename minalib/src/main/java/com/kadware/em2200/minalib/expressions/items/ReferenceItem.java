/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.minalib.expressions.items;

import com.kadware.komodo.baselib.FieldDescriptor;
import com.kadware.em2200.baselib.exceptions.*;
import com.kadware.em2200.minalib.*;
import com.kadware.em2200.minalib.diagnostics.*;
import com.kadware.em2200.minalib.dictionary.*;
import com.kadware.em2200.minalib.exceptions.*;
import com.kadware.em2200.minalib.expressions.builtInFunctions.*;
import com.kadware.em2200.minalib.expressions.Expression;
import com.kadware.komodo.baselib.exceptions.InternalErrorRuntimeException;
import com.kadware.komodo.baselib.exceptions.NotFoundException;

import java.lang.reflect.*;

/**
 * Represents a dictionary reference within an expression.
 * This could be a reference to a simple value, to a node, or to a function.
 * It is generated from parsing something as follows:
 *      {identifier} [ '(' {expression_list} ')' ]
 * Where the expression list contains zero or more zero-delimited expressions.
 * Due to the overloading of synax across these potential reference types,
 * we don't actually know what the reference is until evaluation time, at which point
 * we poll the dictionary to figure out what the code is really asking for.
 */
public class ReferenceItem extends OperandItem {

    private final Expression[] _expressions;
    private final String _reference;

    /**
     * constructor
     * @param locale location of this entity
     * @param reference reference for this entity
     * @param expressions expressions defining the node selectors for a node reference or arguments for a function call.
     *                    Note that a null reference here means that no parenthesis were specified, which means
     *                      () we have a reference to a simple label, or
     *                      () a reference to the number of top-level members of a node, or
     *                      () a function which accepts zero or more arguments
     *                    while a reference to an empty array menas
     *                      () a reference to the number of top-level members of a node, or
     *                      () a function which accepts zero or more arguments
     */
    public ReferenceItem(
        final Locale locale,
        final String reference,
        final Expression[] expressions
    ) {
        super(locale);
        _reference = reference;
        _expressions = expressions;
    }


    /**
     * Evaluates the reference based on the dictionary
     * @param context context of execution
     * @return true if successful, false to discontinue evaluation
     * @throws ExpressionException if something goes wrong with the process (we presume something has been posted to diagnostics)
     */
    @Override
    public Value resolve(
        final Context context
    ) throws ExpressionException {
        try {
            //  Look up the reference in the dictionary.
            //  It must be a particular type of value, else we have an expression exception.
            Value v = context.getDictionary().getValue(_reference);
            switch (v.getType()) {
                case Integer: {
                    //  If this has a location counter index associated with it, we don't want to resolve it yet
                    //  because we might need it for a linker special thing such as LBDICALL$.
                    //  If this is the case, then we produce an undefined reference instead of resolving...
                    IntegerValue iv = (IntegerValue) v;
                    for (UndefinedReference ur : iv._undefinedReferences) {
                        if (ur instanceof UndefinedReferenceToLocationCounter) {
                            UndefinedReference[] refs = {
                                new UndefinedReferenceToLabel(new FieldDescriptor(0, 36),
                                                              false,
                                                              _reference)
                            };
                            return new IntegerValue(false, 0, refs);
                        }
                    }

                    //  Otherwise, resolve the label
                    return resolveLabel(context, v);
                }

                case FloatingPoint:
                case String:
                    return resolveLabel(context, v);

                case BuiltInFunction:
                    return resolveFunction(context, v);

                case Node:
                    return resolveNode(context, v);

                case UserFunction:
                    //TODO

                default:
                    context.appendDiagnostic(new ValueDiagnostic( _locale, "Wrong value type referenced"));
                    throw new ExpressionException();
            }
        } catch (NotFoundException ex) {
            //  reference not found - create an IntegerValue with a value of zero
            //  and an attached positive UndefinedReference.
            UndefinedReference[] refs = {
                new UndefinedReferenceToLabel(new FieldDescriptor(0, 36),
                                              false,
                                              _reference)
            };
            return new IntegerValue(false, 0, refs);
        }
    }

    /**
     * Resolves a value on the assumption it is a function call
     * @param context context of execution
     * @param value the value which serves as the source of the resolution
     * @return true if successful, false to discontinue evaluation
     * @throws ExpressionException if something goes wrong with the process (we presume something has been posted to diagnostics)
     */
    private Value resolveFunction(
        final Context context,
        final Value value
    ) throws ExpressionException {
        try {
            Class<?>[] argTypes = { Locale.class, Expression[].class };
            Class<?> clazz = ((BuiltInFunctionValue)value).getClazz();
            Constructor<?> ctor = clazz.getConstructor(argTypes);
            Object[] ctorArgs = {
                _locale,
                _expressions == null ? new Expression[0] : _expressions
            };
            BuiltInFunction bif = (BuiltInFunction)(ctor.newInstance(ctorArgs));
            return bif.evaluate(context);
        } catch (IllegalAccessException
                | InstantiationException
                | InvocationTargetException
                | NoSuchMethodException ex) {
            throw new InternalErrorRuntimeException(String.format("Caught:%s in ExpressonParser.parseFunction()", ex));
        }
    }

    /**
     * Resolves a value on the assumption it is a simple label
     * @param context context of execution
     * @param value the value which serves as the source of the resolution
     * @return true if successful, false to discontinue evaluation
     * @throws ExpressionException if something goes wrong with the process (we presume something has been posted to diagnostics)
     */
    private Value resolveLabel(
        final Context context,
        final Value value
    ) throws ExpressionException {
        if (_expressions != null) {
            context.appendDiagnostic(
                new ErrorDiagnostic(_locale,
                                    "Attempt to apply node selectors to a non-node value"));
            throw new ExpressionException();
        }
        return value;
    }

    /**
     * Resolves a value on the assumption it is a node.
     * @param context context of execution
     * @param value the value which serves as the source of the resolution
     * @return true if successful, false to discontinue evaluation
     * @throws ExpressionException if something goes wrong with the process (we presume something has been posted to diagnostics)
     */
    private Value resolveNode(
        final Context context,
        final Value value
    ) throws ExpressionException {
        Value currentValue = value;
        if (_expressions != null) {
            for (Expression exp : _expressions) {
                if (!(currentValue instanceof NodeValue)) {
                    context.appendDiagnostic(
                        new ValueDiagnostic(_locale,
                                            "Arity of reference exceeds arity of node"));
                    throw new ExpressionException();
                }

                Value selectorValue = exp.evaluate(context);
                try {
                    currentValue = ((NodeValue) currentValue).getValue(selectorValue);
                } catch (NotFoundException ex) {
                    context.appendDiagnostic(
                        new ValueDiagnostic(_locale,
                                            "Selector not found in node reference"));
                    throw new ExpressionException();
                }
            }
        }

        //  If we're still sitting on a NodeValue, return the number of child values.
        //  Otherwise, just return the node.
        if (currentValue instanceof NodeValue) {
            return new IntegerValue(false, ((NodeValue) currentValue).getValueCount(), null);
        } else {
            return currentValue;
        }
    }
}
