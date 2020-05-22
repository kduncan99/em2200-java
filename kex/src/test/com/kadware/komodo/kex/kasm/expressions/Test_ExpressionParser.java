/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm.expressions;

import com.kadware.komodo.baselib.exceptions.CharacteristicOverflowException;
import com.kadware.komodo.baselib.exceptions.CharacteristicUnderflowException;
import com.kadware.komodo.kex.kasm.*;
import com.kadware.komodo.kex.kasm.dictionary.*;
import com.kadware.komodo.kex.kasm.exceptions.ExpressionException;
import com.kadware.komodo.kex.kasm.expressions.items.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class Test_ExpressionParser {

    @Test
    public void parseFloatingLiteral(
    ) throws CharacteristicOverflowException,
             CharacteristicUnderflowException,
             ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("3.14159", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(1, exp._items.size());
        IExpressionItem item = exp._items.get(0);
        assertTrue(item instanceof ValueItem);
        Value v = ((ValueItem)item)._value;
        assertTrue(v instanceof FloatingPointValue);
        assertEquals(3.14159, ((FloatingPointValue)v)._value.toDouble(), 0.00001);
    }

    @Test
    public void parseIntegerLiteral(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("14458", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(1, exp._items.size());
        IExpressionItem item = exp._items.get(0);
        assertTrue(item instanceof ValueItem);
        Value v = ((ValueItem)item)._value;
        assertTrue(v instanceof IntegerValue);
        assertEquals(14458L, ((IntegerValue)v)._value.get().longValue());
    }

    @Test
    public void parseNegativeIntegerLiteral(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("-14458", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(2, exp._items.size());

        IExpressionItem item0 = exp._items.get(0);
        assertTrue(item0 instanceof OperatorItem);
//        Operator op = ((OperatorItem) item0)._operator; //  TODO check this value if we can, somehow

        IExpressionItem item1 = exp._items.get(1);
        assertTrue(item1 instanceof ValueItem);
        Value v1 = ((ValueItem)item1)._value;
        assertTrue(v1 instanceof IntegerValue);
        assertEquals(14458L, ((IntegerValue)v1)._value.get().longValue());
    }

    @Test
    public void parseStringLiteral(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("'Hello'", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(1, exp._items.size());
        IExpressionItem item = exp._items.get(0);
        assertTrue(item instanceof ValueItem);
        Value v = ((ValueItem)item)._value;
        assertTrue(v instanceof StringValue);
        assertEquals("Hello", ((StringValue)v)._value);
    }

    @Test
    public void simpleConcatenation(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("'Hello ':'Stupid ':'Moron'", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(5, exp._items.size());
    }

    @Test
    public void simpleMath(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("1+3", locale);

        Expression exp = parser.parse(new Assembler.Builder().build());
        assertEquals(3, exp._items.size());
    }

    @Test
    public void parseLabel(
    ) {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("$Label", locale);

        String label = parser.parseLabel(new Assembler.Builder().build());
        assertEquals("$Label", label);
    }

    @Test
    public void parseBuiltInFunction(
    ) throws ExpressionException {
        LineSpecifier ls = new LineSpecifier(0, 10);
        Locale locale = new Locale(ls, 10);
        ExpressionParser parser = new ExpressionParser("$sl('Test')", locale);

        assertNotNull(parser.parseReference(new Assembler.Builder().build()));
    }
}